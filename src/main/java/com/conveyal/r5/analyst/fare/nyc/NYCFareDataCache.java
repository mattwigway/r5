package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.iterator.TObjectIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** An NYCFareDataCache contains fare data specific to NYC, for a specific transitlayer */
public final class NYCFareDataCache {
    private static final Logger LOG = LoggerFactory.getLogger(NYCFareDataCache.class);

    public final TIntObjectMap<LIRRStop> lirrStopForTransitLayerStop = new TIntObjectHashMap<>();
    public final TObjectIntMap<String> transitLayerStopForMnrStop = new TObjectIntHashMap<>();
    public final TIntSet peakLirrPatterns = new TIntHashSet();
    public final TIntSet allLirrPatterns = new TIntHashSet();
    public NYCInRoutingFareCalculator.NYCPatternType[] patternTypeForPattern;
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
    public final TIntObjectMap<NYCInRoutingFareCalculator.MetroNorthLine> mnrLineForPattern = new TIntObjectHashMap<>();


    public NYCFareDataCache(TransitLayer transitLayer) {
        patternTypeForPattern = new NYCInRoutingFareCalculator.NYCPatternType[transitLayer.tripPatterns.size()];

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
                    patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.LIRR_PEAK;
                } else {
                    patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.LIRR_OFFPEAK;
                }
            } else if (routeId.startsWith("mnr")) {
                if (routeId.endsWith("offpeak")) patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.METRO_NORTH_OFFPEAK;
                else patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.METRO_NORTH_PEAK;

                // figure out what line it's on
                String routeLongName = transitLayer.routes.get(pat.routeIndex).route_long_name;

                if (routeLongName.equals("Harlem")) mnrLineForPattern.put(i, NYCInRoutingFareCalculator.MetroNorthLine.HARLEM);
                else if (routeLongName.equals("Hudson")) mnrLineForPattern.put(i, NYCInRoutingFareCalculator.MetroNorthLine.HUDSON);
                // New Haven line has many branches
                else if (routeLongName.equals("New Haven") || routeLongName.equals("New Canaan") ||
                        routeLongName.equals("Waterbury") || routeLongName.equals("Danbury") ||
                        routeLongName.equals("MNR Shore Line East")) mnrLineForPattern.put(i, NYCInRoutingFareCalculator.MetroNorthLine.NEW_HAVEN);
                else throw new IllegalStateException("Unrecognized Metro-North route_long_name " + routeLongName);
            } else if (routeId.startsWith("bus")) {
                // Figure out if it's a local bus or an express bus
                String[] split = routeId.split("_");
                String rawRouteId = split[split.length - 1]; // the original GTFS route ID
                if (NYCStaticFareData.expressBusRoutes.contains(rawRouteId)) {
                    patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.METROCARD_EXPRESS_BUS;
                } else {
                    patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.METROCARD_LOCAL_BUS;
                }
            } else if (routeId.startsWith("nyct_subway")) {
                // Figure out if it's the Staten Island Railway
                if (routeId.equals("nyct_subway_SI")) patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.STATEN_ISLAND_RWY;
                else patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.METROCARD_SUBWAY;
            } else if (routeId.startsWith("si-ferry")) {
                patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.STATEN_ISLAND_FERRY;
            } else if (routeId.startsWith("ferry")) {
                // NYC Ferry
                // figure out if it's a real ferry, or a free shuttle bus
                int routeType = transitLayer.routes.get(pat.routeIndex).route_type;

                if (routeType == 4) patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.NYC_FERRY; // boat
                else if (routeType == 3) patternTypeForPattern[i] = NYCInRoutingFareCalculator.NYCPatternType.NYC_FERRY_BUS; // free shuttle bus
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
        TObjectIntMap<NYCInRoutingFareCalculator.NYCPatternType> hist = new TObjectIntHashMap<>();
        for (NYCInRoutingFareCalculator.NYCPatternType type : NYCInRoutingFareCalculator.NYCPatternType
                .values()) hist.put(type, 0);
        for (int i = 0; i < patternTypeForPattern.length; i++) {
            NYCInRoutingFareCalculator.NYCPatternType type = patternTypeForPattern[i];
            if (type == null) {
                TripPattern pat = transitLayer.tripPatterns.get(i);
                throw new NullPointerException("Pattern type is null for pattern on route " + pat.routeId);
            } else {
                hist.increment(type);
            }
        }
        LOG.info("NYC fare pattern types:");
        for (TObjectIntIterator<NYCInRoutingFareCalculator.NYCPatternType> it = hist.iterator(); it.hasNext();) {
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
