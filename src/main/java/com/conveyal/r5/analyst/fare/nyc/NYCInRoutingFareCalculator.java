package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * An in-routing fare calculator for East-of-Hudson services in the NYC area.
 *
 * @author mattwigway
 */

public class NYCInRoutingFareCalculator extends InRoutingFareCalculator {
    private static final Logger LOG = LoggerFactory.getLogger(NYCInRoutingFareCalculator.class);

    /**
     * weak hash map from transit layer to cached NYC-specific fare data about that transit layer,
     * weak so that it doesn't prevent GC of no longer used transport networks (though this is largely hypothetical,
     * as Analysis workers never load more than a single transport network.
     */
    private static final Map<TransitLayer, NYCFareDataCache> fareDataForTransitLayer = new WeakHashMap<>();

    /** Create the cached fare data iff there is a cache mix, otherwise just return it */
    public NYCFareDataCache getOrCreateFareData () {
        if (!fareDataForTransitLayer.containsKey(transitLayer)) {
            synchronized (fareDataForTransitLayer) {
                if (!fareDataForTransitLayer.containsKey(transitLayer)) {
                    LOG.info("Initializing NYC InRoutingFareCalculator");
                    NYCFareDataCache fareData = new NYCFareDataCache();

                    for (int i = 0; i < this.transitLayer.stopIdForIndex.size(); i++) {
                        // slow but only happens during initialization
                        String prefixedStopId = this.transitLayer.stopIdForIndex.get(i);
                        String stopId = prefixedStopId.split(":", 2)[1]; // get rid of feed id
                        if (stopId.startsWith("lirr")) {
                            fareData.lirrStopForTransitLayerStop.put(i, LIRRStop.valueOf(stopId.toUpperCase(Locale.US)));
                        }
                    }

                    for (int i = 0; i < this.transitLayer.tripPatterns.size(); i++) {
                        TripPattern pat = this.transitLayer.tripPatterns.get(i);
                        if (pat.routeId.startsWith("lirr")) {
                            fareData.allLirrPatterns.add(i);
                            if (!pat.routeId.endsWith("offpeak")) fareData.peakLirrPatterns.add(i);
                        }
                    }

                    fareDataForTransitLayer.put(transitLayer, fareData);
                }
            }
        }

        return fareDataForTransitLayer.get(transitLayer);
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        NYCFareDataCache fareData = getOrCreateFareData();

        // Extract relevant data about rides
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();

        McRaptorSuboptimalPathProfileRouter.McRaptorState stateForTraversal = state;
        while (stateForTraversal != null) {
            if (stateForTraversal.pattern == -1) {
                stateForTraversal = stateForTraversal.back;
                continue; // on the street, not on transit
            }
            patterns.add(stateForTraversal.pattern);
            alightStops.add(stateForTraversal.stop);
            boardStops.add(transitLayer.tripPatterns.get(stateForTraversal.pattern).stops[stateForTraversal.boardStopPosition]);
            boardTimes.add(stateForTraversal.boardTime);
            stateForTraversal = stateForTraversal.back;
        }

        // reverse data about the rides so we can step forward through them
        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        boardTimes.reverse();

        List<LIRRStop> lirrBoardStops = null;
        List<LIRRStop> lirrAlightStops = null;
        List<LIRRTransferAllowance.LIRRDirection> lirrDirections = null;
        BitSet lirrPeak = new BitSet();
        int lirrRideIndex = 0;

        int cumulativeFare = 0;
        LIRRTransferAllowance lirr = null;

        int previousAlightStop = -1;

        // TODO I don't think this is handling street transfers (-1 pattern) right
        for (int i = 0; i < patterns.size(); i++) {
            int pattern = patterns.get(i);
            int boardStop = boardStops.get(i);
            int alightStop = alightStops.get(i);
            int boardTime = boardTimes.get(i);

            // LONG ISLAND RAIL ROAD
            if (fareData.allLirrPatterns.contains(pattern)) {
                if (lirrBoardStops == null) {
                    // new ride on the LIRR
                    lirrBoardStops = new ArrayList<>();
                    lirrAlightStops = new ArrayList<>();
                    lirrDirections = new ArrayList<>();
                    lirrRideIndex = 0;

                    lirrBoardStops.add(fareData.lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(fareData.lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, fareData.peakLirrPatterns.contains(pattern));
                } else if (boardStop == previousAlightStop) {
                    // continue existing ride on the LIRR
                    lirrRideIndex++;
                    lirrBoardStops.add(fareData.lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(fareData.lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, fareData.peakLirrPatterns.contains(pattern));

                } else {
                    // we have two adjacent rides on the LIRR, but involving an on-street transfer
                    // first, increment cumulative fare. Don't set transfer allowance, as the street transfer clears it.
                    cumulativeFare += new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak).cumulativeFare;

                    // now, behave like a new LIRR ride
                    lirrBoardStops = new ArrayList<>();
                    lirrAlightStops = new ArrayList<>();
                    lirrDirections = new ArrayList<>();
                    lirrRideIndex = 0;

                    lirrBoardStops.add(fareData.lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(fareData.lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, fareData.peakLirrPatterns.contains(pattern));
                }
            } else if (lirrBoardStops != null) {
                // we have left the LIRR
                lirr = new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak);
                cumulativeFare += lirr.cumulativeFare;
                lirr = null;
                lirrBoardStops = null;
                lirrAlightStops = null;
                lirrPeak = null;
                lirrRideIndex = 0;
            }

            // PREPARE FOR NEXT ITERATION
            previousAlightStop = alightStop;
        }

        // IF WE ENDED ON THE LIRR, ADD THE FARE AND RECORD THE FARE SO FAR
        // This also happens in the loop above, when another transit service is ridden after the LIRR, or when
        // there is an on-street transfer between LIRR lines
        if (lirrBoardStops != null) {
            lirr = new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak);
            cumulativeFare += lirr.cumulativeFare;
        }

        return new FareBounds(cumulativeFare, new NYCTransferAllowance(lirr));
    }

    @Override
    public String getType() {
        return "nyc";
    }

    public static final class NYCFareDataCache {
        public final TIntObjectMap<LIRRStop> lirrStopForTransitLayerStop = new TIntObjectHashMap<>();
        public final TIntSet peakLirrPatterns = new TIntHashSet();
        public final TIntSet allLirrPatterns = new TIntHashSet();
    }
}
