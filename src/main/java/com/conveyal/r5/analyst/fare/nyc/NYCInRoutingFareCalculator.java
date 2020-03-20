package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
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

    public final TIntObjectMap<LIRRStop> lirrStopForTransitLayerStop = new TIntObjectHashMap<>();
    public final TIntSet peakLirrPatterns = new TIntHashSet();
    public final TIntSet allLirrPatterns = new TIntHashSet();

    @Override
    public void initialize () {
        LOG.info("Initializing NYC InRoutingFareCalculator");

        for (int i = 0; i < this.transitLayer.stopIdForIndex.size(); i++) {
            String stopId = this.transitLayer.stopIdForIndex.get(i);
            if (stopId.startsWith("lirr")) {
                lirrStopForTransitLayerStop.put(i, LIRRStop.valueOf(stopId.toUpperCase(Locale.US)));
            }
        }

        for (int i = 0; i < this.transitLayer.tripPatterns.size(); i++) {
            TripPattern pat = this.transitLayer.tripPatterns.get(i);
            if (pat.routeId.startsWith("lirr")) {
                allLirrPatterns.add(i);
                if (!pat.routeId.endsWith("offpeak")) peakLirrPatterns.add(i);
            }
        }
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
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

        for (int i = 0; i < patterns.size(); i++) {
            int pattern = patterns.get(i);
            int boardStop = boardStops.get(i);
            int alightStop = alightStops.get(i);
            int boardTime = boardTimes.get(i);

            // LONG ISLAND RAIL ROAD
            if (allLirrPatterns.contains(pattern)) {
                if (lirrBoardStops == null) {
                    // new ride on the LIRR
                    lirrBoardStops = new ArrayList<>();
                    lirrAlightStops = new ArrayList<>();
                    lirrDirections = new ArrayList<>();
                    lirrRideIndex = 0;

                    lirrBoardStops.add(lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, peakLirrPatterns.contains(pattern));
                } else if (boardStop == previousAlightStop) {
                    // continue existing ride on the LIRR
                    lirrRideIndex++;
                    lirrBoardStops.add(lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, peakLirrPatterns.contains(pattern));

                } else {
                    // we have two adjacent rides on the LIRR, but involving an on-street transfer
                    // first, increment cumulative fare. Don't set transfer allowance, as the street transfer clears it.
                    cumulativeFare += new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak).cumulativeFare;

                    // now, behave like a new LIRR ride
                    lirrBoardStops = new ArrayList<>();
                    lirrAlightStops = new ArrayList<>();
                    lirrDirections = new ArrayList<>();
                    lirrRideIndex = 0;

                    lirrBoardStops.add(lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, peakLirrPatterns.contains(pattern));
                }
            } else if (lirrBoardStops != null) {
                // we have left the LIRR
                lirr = new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak);
                cumulativeFare += lirr.cumulativeFare;
                lirrBoardStops = null;
                lirrAlightStops = null;
                lirrPeak = null;
                lirrRideIndex = 0;
            }

            // PREPARE FOR NEXT ITERATION
            previousAlightStop = alightStop;
        }

        return new FareBounds(cumulativeFare, new NYCTransferAllowance(lirr));
    }

    @Override
    public String getType() {
        return "nyc";
    }
}
