package com.conveyal.r5.analyst.fare.nyc;

import com.csvreader.CsvReader;
import com.google.common.collect.Sets;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.conveyal.r5.analyst.fare.nyc.NYCInRoutingFareCalculator.NYCPatternType;

/** A class that contains a bunch of static data about fares in NYC */
public class NYCStaticFareData {
    public static final int LOCAL_BUS_SUBWAY_FARE = 275;
    public static final int EXPRESS_BUS_FARE = 675;
    public static final int EXPRESS_BUS_UPGRADE = 375;
    public static final int METROCARD_TRANSFER_VALIDITY_TIME_SECONDS = 2 * 60 * 60;
    public static final int NYC_FERRY_FARE = 275;
    public static final int METRO_NORTH_MAX_FARE;

    public static final TObjectIntMap<NYCPatternType> METROCARD_TRANSFER_ALLOWANCE_FOR_PATTERN_TYPE =
            new TObjectIntHashMap<>();

    static {
        // due to a likely mistake in the NYC transfer system, transferring from a 2.75 subway/bus to a 6.75 express bus
        // is a 3.75 upgrade, meaning that subway + express bus = 2.75 + 3.75 = 6.50. I suspect they meant to make the
        // transfer amount 4.25 and they messed up on the math. Anyhow, that means that max transfer allowance from the
        // subway/local bus is 3.00...
        METROCARD_TRANSFER_ALLOWANCE_FOR_PATTERN_TYPE.put(NYCPatternType.METROCARD_SUBWAY, 300);
        METROCARD_TRANSFER_ALLOWANCE_FOR_PATTERN_TYPE.put(NYCPatternType.METROCARD_LOCAL_BUS, 300);
        METROCARD_TRANSFER_ALLOWANCE_FOR_PATTERN_TYPE.put(NYCPatternType.STATEN_ISLAND_RWY, 300);
        METROCARD_TRANSFER_ALLOWANCE_FOR_PATTERN_TYPE.put(NYCPatternType.METROCARD_EXPRESS_BUS, 275);
    }

    private static final Logger LOG = LoggerFactory.getLogger(NYCStaticFareData.class);

    public static final Set<String> expressBusRoutes = new HashSet<>();

    /** Peak fares on Metro-North, retrieve with .get(from).get(to) */
    public static final Map<String, TObjectIntMap<String>> mnrPeakFares = new HashMap<>();

    /** Offpeak fares on Metro-North, retrieve with .get(from).get(to) */
    public static final Map<String, TObjectIntMap<String>> mnrOffpeakFares = new HashMap<>();

    /**
     * Map from subway stop IDs to station complex IDs. Any stops in the same same station complex have free transfers
     * The station complex IDs are interned so that == can be used efficiently.
     */
    public static final Map<String, String> subwayTransfers = new HashMap<>();

    /** Staten Island Railway only collects fares at two stops: St George and Tompkinsville. These are the IDs of those stops */
    public static final Set<String> statenIslandRwyFareStops = Sets.newHashSet(
            "nyct_subway_S31", "nyct_subway_S31N", "nyct_subway_S31S", // St George
            "nyct_subway_S30", "nyct_subway_S30N", "nyct_subway_S30S" // Tompkinsville
    );

    static {
        readExpressBusRoutes();
        readSubwayTransfers();
        METRO_NORTH_MAX_FARE = readMetroNorthFares();
    }

    private static void readExpressBusRoutes () {
        LOG.info("Reading express bus routes");
        readCsvFromClasspath("fares/nyc/mta/express_bus_routes.csv", rdr -> expressBusRoutes.add(rdr.get("route_id")));
    }

    private static void readSubwayTransfers () {
        LOG.info("Reading subway transfers");
        readCsvFromClasspath("fares/nyc/mta/subway_transfers.csv", rdr -> {
                String stop = rdr.get("stop_id");

                if (subwayTransfers.containsKey(stop)) throw new IllegalStateException("Duplicate stop " + stop + " in subway_transfers.csv!");

                subwayTransfers.put(stop, rdr.get("fare_area_id").intern()); // intern for efficiency
            });
    }

    private static int readMetroNorthFares () {
        int[] maxFare = new int[] {0};

        LOG.info("Reading Metro-North fares");
        readCsvFromClasspath("fares/nyc/mnr/mnr_fares.csv", rdr -> {
            String fromStopId = rdr.get("from_stop_id");
            String toStopId = rdr.get("to_stop_id");
            int peakFare = Integer.parseInt(rdr.get("peak"));
            int offpeakFare = Integer.parseInt(rdr.get("offpeak"));

            maxFare[0] = Math.max(peakFare, maxFare[0]);
            maxFare[0] = Math.max(offpeakFare, maxFare[0]);

            mnrPeakFares.computeIfAbsent(fromStopId, k -> new TObjectIntHashMap<>()).put(toStopId, peakFare);
            mnrOffpeakFares.computeIfAbsent(fromStopId, k -> new TObjectIntHashMap<>()).put(toStopId, offpeakFare);
        });

        // return rather than setting directly since max_fare is final
        return maxFare[0];
    }

    /** Read a CSV file from the classpath, calling forEachRow with the CSV reader as a parameter after the reader
     * has been advanced to each row.
     * @param name Filename on classpath
     */
    private static void readCsvFromClasspath (String name, IOExceptionConsumer<CsvReader> forEachRow) {
        InputStream is = null;
        try {
            is = NYCStaticFareData.class.getClassLoader().getResourceAsStream(name);
            CsvReader rdr = new CsvReader(is, ',', StandardCharsets.UTF_8);
            rdr.readHeaders();
            while (rdr.readRecord()) forEachRow.accept(rdr);
        } catch (IOException e) {
            LOG.error("IO Exception reading {}", name, e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing {}", name, e);
            }
        }
    }

    /** Just a consumer that allows throwing an IOException */
    @FunctionalInterface
    private interface IOExceptionConsumer<T>  {
        void accept (T t) throws IOException;
    }
}
