package com.conveyal.r5.analyst.fare;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.FareDominatingList;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;

import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntIntMap;

public class FareBisect {
    private static final Logger LOG = LoggerFactory.getLogger(FareBisect.class);
    public static void main (String[] args) throws Exception {
        LOG.info("Building network");
        TransportNetwork net = TransportNetwork.fromFiles(args[0],
            Arrays.stream(args, 1, args.length).collect(Collectors.toList()));
        String reqJson = new StringBuilder()
            .append("{")
            .append("    \"fromLat\": 42.26004073751628,")
            .append("    \"fromLon\": -70.90295067955323,")
            .append("    \"toLat\": 42.36108082586396,")
            .append("    \"toLon\": -71.06302867259168,")
            .append("    \"fromTime\": 25200,")
            .append("    \"toTime\": 25260,")
            .append("    \"walkSpeed\": 1.3888888,")
            .append("    \"bikeSpeed\": 4.1666665,")
            .append("    \"bikeTrafficStress\": 4,")
            .append("    \"carSpeed\": 20,")
            .append("    \"streetTime\": 90,")
            .append("    \"maxWalkTime\": 20,")
            .append("    \"maxBikeTime\": 20,")
            .append("    \"maxCarTime\": 45,")
            .append("    \"minBikeTime\": 10,")
            .append("    \"minCarTime\": 10,")
            .append("    \"date\": \"2018-07-16\",")
            .append("    \"limit\": 0,")
            .append("    \"accessModes\": \"WALK\",")
            .append("    \"egressModes\": \"WALK\",")
            .append("    \"directModes\": \"WALK\",")
            .append("    \"transitModes\": \"TRAM,SUBWAY,RAIL,BUS,FERRY,CABLE_CAR,GONDOLA,FUNICULAR\",")
            .append("    \"suboptimalMinutes\": 5,")
            .append("    \"maxTripDurationMinutes\": 120,")
            .append("    \"maxRides\": 4,")
            .append("    \"scenario\": null,")
            .append("    \"scenarioId\": null,")
            .append("    \"zoneId\": \"Z\",")
            .append("    \"wheelchair\": false,")
            .append("    \"maxFare\": 200000,")
            .append("    \"inRoutingFareCalculator\": {")
            .append("      \"type\": \"boston\"")
            .append("    },")
            .append("    \"monteCarloDraws\": 1")
            .append("  }              ")
            .toString();

        ProfileRequest req = JsonUtilities.objectMapper.readValue(reqJson, ProfileRequest.class);
        req.inRoutingFareCalculator.transitLayer = net.transitLayer;

        LOG.info("Performing walk search for access (other access modes not supported)");
        Map<LegMode, TIntIntMap> accessTimes = accessEgressSearch(req.fromLat, req.fromLon, req, net);
        LOG.info("Performing walk search for egress (other access modes not supported)");
        Map<LegMode, TIntIntMap> egressTimes = accessEgressSearch(req.toLat, req.toLon, req, net);

        req.maxTripDurationMinutes = 120; // hack
        IntFunction<DominatingList> listSupplier =
                (departureTime) -> new FareDominatingList(
                        req.inRoutingFareCalculator,
                        req.maxFare,
                        // while I appreciate the use of symbolic constants, I certainly hope the number of seconds per
                        // minute does not change
                        // in fact, we have been moving in the opposite direction with leap-second smearing
                        departureTime + req.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE);

        McRaptorSuboptimalPathProfileRouter mcraptor = new McRaptorSuboptimalPathProfileRouter(
                net,
                req,
                accessTimes,
                egressTimes,
                listSupplier,
                null,
                true); // no collator - route will return states at destination

        for (TIntObjectIterator<Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState>> it =
             mcraptor.finalStatesByDepartureTime.iterator(); it.hasNext();) {
            it.advance();


            int departureTime = it.key();

            for (McRaptorSuboptimalPathProfileRouter.McRaptorState state : it.value()) {
                int totalTimeSeconds = state.time - departureTime;
                if (totalTimeSeconds >= 5325 && totalTimeSeconds <= 5335) {
                    // We have found the trip we found in the Charlie paper
                    System.exit(0);
                } 
            }
        }

        // we did not find the trip we were looking for
        System.exit(1);
    }

    private static Map<LegMode, TIntIntMap> accessEgressSearch (double fromLat, double fromLon, ProfileRequest profileRequest, TransportNetwork net) {
        LOG.info("Performing walk search for access (other access modes not supported)");
        StreetRouter sr = new StreetRouter(net.streetLayer);
        sr.profileRequest = profileRequest;
        sr.timeLimitSeconds = 20 * 60; // hardwired at 20 mins
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;

        if (!sr.setOrigin(fromLat, fromLon)) {
            throw new RuntimeException("Origin or destination not found");
        }

        sr.route();

        TIntIntMap accessTimes = sr.getReachedStops(); // map from stop ID to access time

        if (accessTimes.size() == 0) throw new RuntimeException("No transit near origin!");

        Map<LegMode, TIntIntMap> ret = new HashMap<>();
        ret.put(LegMode.WALK, accessTimes);
        return ret;
    }
}


