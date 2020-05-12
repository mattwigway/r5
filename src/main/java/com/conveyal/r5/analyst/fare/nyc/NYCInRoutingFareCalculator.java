package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.iterator.TObjectIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.lang3.ObjectUtils;
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
                    NYCFareDataCache fareData = new NYCFareDataCache(this.transitLayer);
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
        // not using bitset b/c hard to reverse
        List<Boolean> reachedViaOnStreetTransfer = new ArrayList<>();

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

            boolean onStreetTransfer = stateForTraversal.back != null && stateForTraversal.back.pattern == -1;
            reachedViaOnStreetTransfer.add(onStreetTransfer);

            stateForTraversal = stateForTraversal.back;
        }

        // reverse data about the rides so we can step forward through them
        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        boardTimes.reverse();
        Collections.reverse(reachedViaOnStreetTransfer);

        // TODO put all of these things into objects to make clearing less error-prone
        // metroNorthState = null instead of clearing 5 fields.
        List<LIRRStop> lirrBoardStops = null;
        List<LIRRStop> lirrAlightStops = null;
        List<LIRRTransferAllowance.LIRRDirection> lirrDirections = null;
        BitSet lirrPeak = new BitSet();
        int lirrRideIndex = 0;

        int cumulativeFare = 0;
        LIRRTransferAllowance lirr = null;

        NYCPatternType metrocardTransferSource = null;
        int metrocardTransferExpiry = maxClockTime;

        int previousAlightStop = -1;

        NYCPatternType previousPatternType = null;

        // Metro-North transfer information
        int metroNorthBoardStop = -1;
        int metroNorthAlightStop = -1;
        boolean metroNorthPeak = false;
        // keep track of whether we're on the NH line or a different one, since there are no free
        // transfers from the New Haven line to the Harlem/Hudson lines, at least not with the one-way tickets
        // http://www.iridetheharlemline.com/2010/09/22/question-of-the-day-can-i-use-my-ticket-on-other-lines/
        MetroNorthLine metroNorthLine = null;
        int metroNorthDirection = -1;

        for (int i = 0; i < patterns.size(); i++) {
            int pattern = patterns.get(i);
            int boardStop = boardStops.get(i);
            int alightStop = alightStops.get(i);
            int boardTime = boardTimes.get(i);
            boolean onStreetTransfer = reachedViaOnStreetTransfer.get(i);
            NYCPatternType patternType = fareData.patternTypeForPattern[pattern];
            boolean withinGatesSubwayTransfer = false; // set to true for free within-gates subway transfers

            if (i > 0) {
                // Don't do this at the end of the loop since there is short-circuit logic (continue) below
                previousAlightStop = alightStops.get(i - 1);
                previousPatternType = fareData.patternTypeForPattern[patterns.get(i - 1)];
            }

            // ===== CLEAN UP AFTER LAST RIDE =====
            // PAY FARE UPON LEAVING THE LIRR, EITHER BY RIDING A DIFFERENT TRANSIT SERVICE
            // OR BY TRANSFERRING ON-STREET
            boolean thisPatternLirr = NYCPatternType.LIRR_OFFPEAK.equals(patternType) ||
                    NYCPatternType.LIRR_PEAK.equals(patternType);
            if (lirrBoardStops != null && (onStreetTransfer || !thisPatternLirr)) {
                // we have left the LIRR
                lirr = new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak);
                cumulativeFare += lirr.cumulativeFare;
                lirr = null;
                lirrBoardStops = null;
                lirrAlightStops = null;
                lirrPeak.clear();
                lirrRideIndex = 0;
            }

            // PAY FARE FOR METRO-NORTH AFTER LEAVING
            boolean thisPatternMnr = NYCPatternType.METRO_NORTH_PEAK.equals(patternType) ||
                    NYCPatternType.METRO_NORTH_OFFPEAK.equals(patternType);
            if (metroNorthBoardStop != -1 && (onStreetTransfer || !thisPatternMnr)) {
                cumulativeFare += fareData.getMetroNorthFare(metroNorthBoardStop, metroNorthAlightStop, metroNorthPeak);
                metroNorthBoardStop = metroNorthAlightStop = metroNorthDirection = -1;
                metroNorthPeak = false;
                metroNorthLine = null;
            }

            // CHECK FOR IN-SYSTEM SUBWAY TRANSFERS
            if (NYCPatternType.METROCARD_SUBWAY.equals(patternType) && NYCPatternType.METROCARD_SUBWAY.equals(previousPatternType)) {
                withinGatesSubwayTransfer = hasBehindGatesTransfer(previousAlightStop, boardStop, fareData);
            }

            // ====== PROCESS THIS RIDE ======

            // MTA LOCAL BUS
            if (NYCPatternType.METROCARD_LOCAL_BUS.equals(patternType)) {
                if (metrocardTransferSource == null || boardTime > metrocardTransferExpiry) {
                    // first ride on the local bus, buy a new ticket
                    cumulativeFare += NYCStaticFareData.LOCAL_BUS_SUBWAY_FARE;
                    metrocardTransferSource = NYCPatternType.METROCARD_LOCAL_BUS;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                } else {
                    // use transfer - free transfers to local bus from any MetroCard transferable route
                    // clear transfer information
                    // TODO prohibited bus-bus transfers
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                }
            }

            // MTA EXPRESS BUS
            else if (NYCPatternType.METROCARD_EXPRESS_BUS.equals(patternType)) {
                if (boardTime <= metrocardTransferExpiry &&
                        (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_SUBWAY.equals(metrocardTransferSource) ||
                                NYCPatternType.STATEN_ISLAND_RWY.equals(metrocardTransferSource))) {
                    // transfer from subway or local bus to express bus, pay upgrade fare and clear transfer allowance
                    cumulativeFare += NYCStaticFareData.EXPRESS_BUS_UPGRADE;
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else {
                    // pay new fare
                    cumulativeFare += NYCStaticFareData.EXPRESS_BUS_FARE;
                    metrocardTransferSource = NYCPatternType.METROCARD_EXPRESS_BUS;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                }
            }

            // MTA SUBWAY
            else if (NYCPatternType.METROCARD_SUBWAY.equals(patternType)) {
                if (withinGatesSubwayTransfer) continue; // no fare interaction, no change to transfer allowance
                else {
                    if (boardTime <= metrocardTransferExpiry &&
                            (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                    NYCPatternType.METROCARD_EXPRESS_BUS.equals(metrocardTransferSource) ||
                                    NYCPatternType.STATEN_ISLAND_RWY.equals(metrocardTransferSource))) {
                        // free transfer from bus/SIR to subway, but clear transfer allowance
                        metrocardTransferSource = null;
                        metrocardTransferExpiry = maxClockTime;
                    } else {
                        // pay new fare
                        cumulativeFare += NYCStaticFareData.LOCAL_BUS_SUBWAY_FARE;
                        metrocardTransferSource = NYCPatternType.METROCARD_SUBWAY;
                        metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                    }
                }
            }

            // STATEN ISLAND RAILWAY
            else if (NYCPatternType.STATEN_ISLAND_RWY.equals(patternType)) {
                // Fare is only paid on the SIR at St George and Tompkinsville, both for boarding and alighting.
                // First calculate the full fare, then figure out transfers
                int nFarePayments = 0;
                if (fareData.statenIslandRwyFareStops.contains(boardStop)) nFarePayments++;
                if (fareData.statenIslandRwyFareStops.contains(alightStop)) nFarePayments++;

                if (nFarePayments == 0) continue; // NO FARE INTERACTION, DO NOT UPDATE TRANSFER ALLOWANCES
                else {
                    if (metrocardTransferSource != null && boardTime <= metrocardTransferExpiry) nFarePayments--;
                    if (nFarePayments == 0) {
                        // transfer was used and no new ticket was purchased
                        metrocardTransferSource = null;
                        metrocardTransferExpiry = maxClockTime;
                    } else {
                        // the fare was paid at least once (maybe twice)
                        cumulativeFare += nFarePayments * NYCStaticFareData.LOCAL_BUS_SUBWAY_FARE;
                        metrocardTransferSource = NYCPatternType.STATEN_ISLAND_RWY;
                        metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                    }
                }
            }

            // STATEN ISLAND FERRY
            else if (NYCPatternType.STATEN_ISLAND_FERRY.equals(patternType)) continue; // SI Ferry is free

            // METRO-NORTH RAILROAD
            else if (NYCPatternType.METRO_NORTH_PEAK.equals(patternType) || NYCPatternType.METRO_NORTH_OFFPEAK.equals(patternType)) {
                boolean thisRidePeak = NYCPatternType.METRO_NORTH_PEAK.equals(patternType);
                MetroNorthLine thisRideLine = fareData.mnrLineForPattern.get(pattern);
                int thisRideDirection = transitLayer.tripPatterns.get(pattern).directionId;

                if (metroNorthBoardStop != -1) {
                    // this is the second, etc. ride in a Metro-North trip
                    if (thisRideDirection != metroNorthDirection || thisRideLine != metroNorthLine) {
                        // we have changed direction or line. Pay for the previous ride, and reset the Metro-North transfer allowance
                        // No via fares on Metro-North, unlike LIRR
                        cumulativeFare += fareData.getMetroNorthFare(metroNorthBoardStop, metroNorthAlightStop, metroNorthPeak);
                        metroNorthBoardStop = boardStop;
                        metroNorthAlightStop = alightStop;
                        metroNorthPeak = thisRidePeak;
                        metroNorthDirection = thisRideDirection;
                        metroNorthLine = thisRideLine;
                    } else {
                        // same direction transfer, just extend existing Metro-North ride
                        metroNorthAlightStop = alightStop;
                        metroNorthPeak |= thisRidePeak; // any ride that involves a peak ride anywhere is a peak fare.
                    }
                } else {
                    // new metro-north ride
                    metroNorthBoardStop = boardStop;
                    metroNorthAlightStop = alightStop;
                    metroNorthPeak = thisRidePeak;
                    metroNorthDirection = thisRideDirection;
                    metroNorthLine = thisRideLine;
                }
            }

            // NYC FERRY
            else if (NYCPatternType.NYC_FERRY.equals(patternType)) {
                // simple, just increment the fare, no transfers, don't adjust xfer allowances
                cumulativeFare += NYCStaticFareData.NYC_FERRY_FARE;
            }

            // NYC FERRY FREE SHUTTLE BUS
            // I can't find anywhere where it says that these can only be used in conjunction with
            // a ferry. Pickup_type and dropoff_type is 0 at all stops, but we could change that to
            // only allow pickups/dropoffs at the ferry terminal.
            else if (NYCPatternType.NYC_FERRY_BUS.equals(patternType)) {
                // do nothing, but keep this here so we don't throw an unknown pattern type error later
            }

            // LONG ISLAND RAIL ROAD
            // TODO refactor to use pattern type
            else if (fareData.allLirrPatterns.contains(pattern)) {
                if (lirrBoardStops == null) {
                    // new ride on the LIRR
                    lirrBoardStops = new ArrayList<>();
                    lirrAlightStops = new ArrayList<>();
                    lirrDirections = new ArrayList<>();
                    lirrPeak.clear();
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
                    lirrPeak.clear();
                    lirrRideIndex = 0;

                    lirrBoardStops.add(fareData.lirrStopForTransitLayerStop.get(boardStop));
                    lirrAlightStops.add(fareData.lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, fareData.peakLirrPatterns.contains(pattern));
                }
            } else {
                throw new IllegalStateException("Unrecognized pattern type!");
            }
        }

        // IF WE ENDED ON THE LIRR, ADD THE FARE AND RECORD THE FARE SO FAR
        // This also happens in the loop above, when another transit service is ridden after the LIRR, or when
        // there is an on-street transfer between LIRR lines
        if (lirrBoardStops != null) {
            lirr = new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrPeak);
            cumulativeFare += lirr.cumulativeFare;
        }

        // PAY FARE FOR METRO-NORTH RAILWAY IF THAT WAS THE LAST RIDE
        if (metroNorthBoardStop != -1) {
            cumulativeFare += fareData.getMetroNorthFare(metroNorthBoardStop, metroNorthAlightStop, metroNorthPeak);
        }

        // IF WE HAVE AN ON-STREET TRANSFER, RECORD WHETHER WE'VE LEFT THE LIRR/SUBWAY FOR DOMINATION PURPOSES
        boolean leftSubwayPaidArea = false;
        if (state.pattern == -1) {
            // clear LIRR transfer allowance - no on street transfers with LIRR
            lirr = null;
            // clear MNR transfer allowance - no on street transfers with MNR
            metroNorthBoardStop = metroNorthAlightStop = metroNorthDirection = -1;
            metroNorthPeak = false;
            metroNorthLine = null;
            // record if we've left the subway paid area
            if (NYCPatternType.METROCARD_SUBWAY.equals(previousPatternType)) {
                leftSubwayPaidArea = !hasBehindGatesTransfer(state.back.stop, state.stop, fareData);
            }
        }

        return new FareBounds(cumulativeFare,
                new NYCTransferAllowance(lirr, metrocardTransferSource, metrocardTransferExpiry,
                        leftSubwayPaidArea,
                        metroNorthBoardStop, metroNorthDirection, metroNorthPeak, metroNorthLine
                ));
    }

    /** return true if you can transfer from subway stop from to to without leaving the system */
    public static boolean hasBehindGatesTransfer (int from, int to, NYCFareDataCache fareData) {
        if (from == to) return true; // same platform
        else {
            String previousFareArea = fareData.fareAreaForStop.get(from);
            String thisFareArea = fareData.fareAreaForStop.get(to);
            // both in the same fare area (behind gates)
            // okay to use == here since fare areas are interned - warning can be ignored
            //noinspection StringEquality,ConditionCoveredByFurtherCondition
            return previousFareArea != null && thisFareArea != null && previousFareArea == thisFareArea;
        }
    }

    @Override
    public String getType() {
        return "nyc";
    }

    public static final class NYCFareDataCache {
        public final TIntObjectMap<LIRRStop> lirrStopForTransitLayerStop = new TIntObjectHashMap<>();
        public final TObjectIntMap<String> transitLayerStopForMnrStop = new TObjectIntHashMap<>();
        public final TIntSet peakLirrPatterns = new TIntHashSet();
        public final TIntSet allLirrPatterns = new TIntHashSet();
        public NYCPatternType[] patternTypeForPattern;
        /** St George and Tompkinsville stops where fare is paid on Staten Island Rwy */
        public final TIntSet statenIslandRwyFareStops = new TIntHashSet();

        /** map from stop indices to (interned) fare areas for use in calculating free subway transfers */
        public final TIntObjectMap<String> fareAreaForStop = new TIntObjectHashMap<>();

        /** Metro-North peak fares, map from from stop -> to stop -> fare */
        public final TIntObjectMap<TIntIntMap> mnrPeakFares = new TIntObjectHashMap<>();

        /** Metro-North peak fares, map from from stop -> to stop -> fare */
        public final TIntObjectMap<TIntIntMap> mnrOffpeakFares = new TIntObjectHashMap<>();

        /** Since there are no free transfers betwen lines on Metro-North, keep track of which line
         * we're on.
         */
        public final TIntObjectMap<MetroNorthLine> mnrLineForPattern = new TIntObjectHashMap<>();


        public NYCFareDataCache (TransitLayer transitLayer) {
            patternTypeForPattern = new NYCPatternType[transitLayer.tripPatterns.size()];

            for (int i = 0; i < transitLayer.stopIdForIndex.size(); i++) {
                // slow but only happens during initialization
                String prefixedStopId = transitLayer.stopIdForIndex.get(i);
                String stopId = prefixedStopId.split(":", 2)[1]; // get rid of feed id
                if (stopId.startsWith("lirr")) {
                    lirrStopForTransitLayerStop.put(i, LIRRStop.valueOf(stopId.toUpperCase(Locale.US)));
                } else if (NYCStaticFareData.subwayTransfers.containsKey(stopId)) {
                    fareAreaForStop.put(i, NYCStaticFareData.subwayTransfers.get(stopId));
                } else if (stopId.startsWith("mnr")) {
                    transitLayerStopForMnrStop.put(stopId.substring(4), i); // get rid of mnr_ prefix
                } else if (NYCStaticFareData.statenIslandRwyFareStops.contains(stopId)) statenIslandRwyFareStops.add(i);
            }

            for (int i = 0; i < transitLayer.tripPatterns.size(); i++) {
                TripPattern pat = transitLayer.tripPatterns.get(i);
                String routeId = pat.routeId.split(":", 2)[1]; // get rid of feed ID
                if (routeId.startsWith("lirr")) {
                    allLirrPatterns.add(i);
                    if (!pat.routeId.endsWith("offpeak")) {
                        peakLirrPatterns.add(i);
                        patternTypeForPattern[i] = NYCPatternType.LIRR_PEAK;
                    } else {
                        patternTypeForPattern[i] = NYCPatternType.LIRR_OFFPEAK;
                    }
                } else if (routeId.startsWith("mnr")) {
                    if (routeId.endsWith("offpeak")) patternTypeForPattern[i] = NYCPatternType.METRO_NORTH_OFFPEAK;
                    else patternTypeForPattern[i] = NYCPatternType.METRO_NORTH_PEAK;

                    // figure out what line it's on
                    String routeLongName = transitLayer.routes.get(pat.routeIndex).route_long_name;

                    if (routeLongName.equals("Harlem")) mnrLineForPattern.put(i, MetroNorthLine.HARLEM);
                    else if (routeLongName.equals("Hudson")) mnrLineForPattern.put(i, MetroNorthLine.HUDSON);
                    // New Haven line has many branches
                    else if (routeLongName.equals("New Haven") || routeLongName.equals("New Canaan") ||
                            routeLongName.equals("Waterbury") || routeLongName.equals("Danbury") ||
                            routeLongName.equals("MNR Shore Line East")) mnrLineForPattern.put(i, MetroNorthLine.NEW_HAVEN);
                    else throw new IllegalStateException("Unrecognized Metro-North route_long_name " + routeLongName);
                } else if (routeId.startsWith("bus")) {
                    // Figure out if it's a local bus or an express bus
                    String[] split = routeId.split("_");
                    String rawRouteId = split[split.length - 1]; // the original GTFS route ID
                    if (NYCStaticFareData.expressBusRoutes.contains(rawRouteId)) {
                        patternTypeForPattern[i] = NYCPatternType.METROCARD_EXPRESS_BUS;
                    } else {
                        patternTypeForPattern[i] = NYCPatternType.METROCARD_LOCAL_BUS;
                    }
                } else if (routeId.startsWith("nyct_subway")) {
                    // Figure out if it's the Staten Island Railway
                    if (routeId.equals("nyct_subway_SI")) patternTypeForPattern[i] = NYCPatternType.STATEN_ISLAND_RWY;
                    else patternTypeForPattern[i] = NYCPatternType.METROCARD_SUBWAY;
                } else if (routeId.startsWith("si-ferry")) {
                    patternTypeForPattern[i] = NYCPatternType.STATEN_ISLAND_FERRY;
                } else if (routeId.startsWith("ferry")) {
                    // NYC Ferry
                    // figure out if it's a real ferry, or a free shuttle bus
                    int routeType = transitLayer.routes.get(pat.routeIndex).route_type;

                    if (routeType == 4) patternTypeForPattern[i] = NYCPatternType.NYC_FERRY; // boat
                    else if (routeType == 3) patternTypeForPattern[i] = NYCPatternType.NYC_FERRY_BUS; // free shuttle bus
                    else throw new IllegalArgumentException("unexpected route type in NYC Ferry feed");
                }

                if (patternTypeForPattern[i] == null){
                    throw new IllegalStateException("No pattern type assigned for pattern on route " + routeId);
                }
            }

            // construct MNR fare tables
            NYCStaticFareData.mnrPeakFares.forEach((fromStop, toStops) -> {
                int fromTransitLayerStop = transitLayerStopForMnrStop.get(fromStop);
                TIntIntMap toTransitLayerStops = new TIntIntHashMap();
                toStops.forEachEntry((toStop, fare) -> {
                            int toTransitLayerStop = transitLayerStopForMnrStop.get(toStop);
                            toTransitLayerStops.put(toTransitLayerStop, fare);
                            return true; // continue iteration
                        });
                mnrPeakFares.put(fromTransitLayerStop, toTransitLayerStops);
            });

            NYCStaticFareData.mnrOffpeakFares.forEach((fromStop, toStops) -> {
                int fromTransitLayerStop = transitLayerStopForMnrStop.get(fromStop);
                TIntIntMap toTransitLayerStops = new TIntIntHashMap();
                toStops.forEachEntry((toStop, fare) -> {
                    int toTransitLayerStop = transitLayerStopForMnrStop.get(toStop);
                    toTransitLayerStops.put(toTransitLayerStop, fare);
                    return true; // continue iteration
                });
                mnrOffpeakFares.put(fromTransitLayerStop, toTransitLayerStops);
            });


            // print stats
            TObjectIntMap<NYCPatternType> hist = new TObjectIntHashMap<>();
            for (NYCPatternType type : NYCPatternType.values()) hist.put(type, 0);
            for (int i = 0; i < patternTypeForPattern.length; i++) {
                NYCPatternType type = patternTypeForPattern[i];
                if (type == null) {
                    TripPattern pat = transitLayer.tripPatterns.get(i);
                    throw new NullPointerException("Pattern type is null for pattern on route " + pat.routeId);
                } else {
                    hist.increment(type);
                }
            }
            LOG.info("NYC fare pattern types:");
            for (TObjectIntIterator<NYCPatternType> it = hist.iterator(); it.hasNext();) {
                it.advance();
                LOG.info("  {}: {}", it.key(), it.value());
            }
        }

        public int getMetroNorthFare (int fromStop, int toStop, boolean peak) {
            TIntObjectMap<TIntIntMap> fares = (peak ? mnrPeakFares : mnrOffpeakFares);
            if (!fares.containsKey(fromStop) || !fares.get(fromStop).containsKey(toStop)) {
                throw new IllegalArgumentException("Could not find Metro-North fare!");
            }

            return fares.get(fromStop).get(toStop);
        }
    }

    public enum NYCPatternType {
        // TODO move peakLirrPatterns and allLirrPatterns in here
        /** A local bus ($2.75), with free transfers to subway or other local bus */
        METROCARD_LOCAL_BUS,
        /** NYC subway, free within-system transfers, and free transfers to local bus. 3.75 upgrade to Express Bus */
        METROCARD_SUBWAY,
        /** Staten Island Railway. This is different from the subway because it provides a free transfer to/from all subway lines */
        STATEN_ISLAND_RWY,
        /** MTA Express buses, $6.75 or $3.75 upgrade from local bus (yes, it's cheaper to transfer from local bus) */
        METROCARD_EXPRESS_BUS,
        STATEN_ISLAND_FERRY,
        METRO_NORTH_PEAK,
        METRO_NORTH_OFFPEAK,
        // TODO currently unused
        LIRR_OFFPEAK, LIRR_PEAK,
        NYC_FERRY,
        NYC_FERRY_BUS // Free shuttle bus to NYC Ferry terminals
    }

    public enum MetroNorthLine {
        HARLEM, HUDSON, NEW_HAVEN // and branches
    }
}
