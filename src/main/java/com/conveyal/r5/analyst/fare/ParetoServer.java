package com.conveyal.r5.analyst.fare;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.*;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.*;
import java.util.function.IntFunction;

import static spark.Spark.halt;

/**
 * Server for debug tool for fare analysis - to draw pareto surface and show combinations of travel time and fare.
 */
public class ParetoServer {
    public TransportNetwork transportNetwork;

    private static final Logger LOG = LoggerFactory.getLogger(ParetoServer.class);

    public ParetoServer (TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    public String handle (Request req, Response res) throws IOException {
        ProfileRequest profileRequest = JsonUtilities.objectMapper.readValue(req.body(), ProfileRequest.class);

        if (profileRequest.inRoutingFareCalculator != null) profileRequest.inRoutingFareCalculator.transitLayer = this.transportNetwork.transitLayer;

        // now perform routing - always using McRaptor
        LOG.info("Performing walk search for access (other access modes not supported)");
        Map<LegMode, TIntIntMap> accessTimes = accessEgressSearch(profileRequest.fromLat, profileRequest.fromLon, profileRequest);
        LOG.info("Performing walk search for egress (other access modes not supported)");
        Map<LegMode, TIntIntMap> egressTimes = accessEgressSearch(profileRequest.toLat, profileRequest.toLon, profileRequest);

        LOG.info("Performing multiobjective transit routing");
        long startTime = System.currentTimeMillis();
        IntFunction<DominatingList> listSupplier =
                (departureTime) -> new FareDominatingList(
                        profileRequest.inRoutingFareCalculator,
                        profileRequest.maxFare,
                        // while I appreciate the use of symbolic constants, I certainly hope the number of seconds per
                        // minute does not change
                        // in fact, we have been moving in the opposite direction with leap-second smearing
                        departureTime + profileRequest.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE);
        McRaptorSuboptimalPathProfileRouter mcraptor = new McRaptorSuboptimalPathProfileRouter(
                transportNetwork,
                profileRequest,
                accessTimes,
                egressTimes,
                listSupplier,
                null,
                true); // no collator - route will return states at destination

        mcraptor.route();
        long totalTime = System.currentTimeMillis() - startTime;

        List<ParetoTrip> trips = new ArrayList<>();

        for (TIntObjectIterator<Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState>> it =
             mcraptor.finalStatesByDepartureTime.iterator(); it.hasNext();) {
            it.advance();

            int departureTime = it.key();

            for (McRaptorSuboptimalPathProfileRouter.McRaptorState state : it.value()) {
                trips.add(new ParetoTrip(state, departureTime, transportNetwork));
            }
        }

        ParetoReturn ret = new ParetoReturn(profileRequest, trips, totalTime);

        res.header("Content-Type", "application/json");

        return JsonUtilities.objectMapper.writeValueAsString(ret);
    }

    private Map<LegMode, TIntIntMap> accessEgressSearch (double fromLat, double fromLon, ProfileRequest profileRequest) {
        LOG.info("Performing walk search for access (other access modes not supported)");
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = profileRequest;
        sr.timeLimitSeconds = 20 * 60; // hardwired at 20 mins
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;

        if (!sr.setOrigin(fromLat, fromLon)) {
            halt(404, "Origin or destination not found");
        }

        sr.route();

        TIntIntMap accessTimes = sr.getReachedStops(); // map from stop ID to access time

        if (accessTimes.size() == 0) halt(404, "No transit near origin!");

        Map<LegMode, TIntIntMap> ret = new HashMap<>();
        ret.put(LegMode.WALK, accessTimes);
        return ret;
    }

    /**
     * Many happy returns - class to encapsulate return value.
     */
    public static final class ParetoReturn {
        public final ProfileRequest request;
        public final Collection<ParetoTrip> trips;
        public final long computeTimeMillis;

        public ParetoReturn(ProfileRequest request, Collection<ParetoTrip> trips, long computeTimeMillis) {
            this.request = request;
            this.trips = trips;
            this.computeTimeMillis = computeTimeMillis;
        }
    }

    public static final class ParetoTrip {
        public final int durationSeconds;
        public final int departureTime;
        public final int fare;
        public final List<ParetoLeg> legs;

        public ParetoTrip (McRaptorSuboptimalPathProfileRouter.McRaptorState state, int departureTime, TransportNetwork network) {
            this.departureTime = departureTime;
            this.durationSeconds = state.time - departureTime;
            this.fare = state.fare.cumulativeFarePaid;

            legs = new ArrayList<>();

            while (state.back != null) {
                if (state.pattern != -1) {
                    TripPattern pattern = network.transitLayer.tripPatterns.get(state.pattern);

                    legs.add(new ParetoLeg(
                            network.transitLayer.routes.get(pattern.routeIndex),
                            network.transitLayer.stopNames.get(pattern.stops[state.boardStopPosition]),
                            network.transitLayer.stopNames.get(pattern.stops[state.alightStopPosition]),
                            state.boardTime,
                            state.time
                            ));
                }

                state = state.back;
            }

            Collections.reverse(legs);
        }

    }

    public static final class ParetoLeg {
        public final RouteInfo route;
        public final String boardStop;
        public final String alightStop;
        public final int boardTime;
        public final int alightTime;

        public ParetoLeg(RouteInfo route, String boardStop, String alightStop, int boardTime, int alightTime) {
            this.route = route;
            this.boardStop = boardStop;
            this.alightStop = alightStop;
            this.boardTime = boardTime;
            this.alightTime = alightTime;
        }
    }
}
