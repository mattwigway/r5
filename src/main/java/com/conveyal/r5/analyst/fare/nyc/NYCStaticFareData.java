package com.conveyal.r5.analyst.fare.nyc;

import com.csvreader.CsvReader;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A class that contains a bunch of static data about fares in NYC */
public class NYCStaticFareData {
    public static final int LOCAL_BUS_SUBWAY_FARE = 275;
    public static final int EXPRESS_BUS_FARE = 675;
    public static final int EXPRESS_BUS_UPGRADE = 375;
    public static final int METROCARD_TRANSFER_VALIDITY_TIME_SECONDS = 2 * 60 * 60;

    private static final Logger LOG = LoggerFactory.getLogger(NYCStaticFareData.class);

    public static final Set<String> expressBusRoutes = new HashSet<>();

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
    }

    private static void readExpressBusRoutes () {
        LOG.info("Reading express bus routes");

        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/mta/express_bus_routes.csv");
            CsvReader rdr = new CsvReader(is, ',', StandardCharsets.UTF_8);
            rdr.readHeaders();
            while (rdr.readRecord()) {
                expressBusRoutes.add(rdr.get("route_id"));
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading express bus routes", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing express bus routes", e);
            }
        }
    }

    private static void readSubwayTransfers () {
        LOG.info("Reading subway transfers");

        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/mta/subway_transfers.csv");
            CsvReader rdr = new CsvReader(is, ',', StandardCharsets.UTF_8);
            rdr.readHeaders();
            while (rdr.readRecord()) {
                String stop = rdr.get("stop_id");

                if (subwayTransfers.containsKey(stop)) throw new IllegalStateException("Duplicate stop " + stop + " in subway_transfers.csv!");

                subwayTransfers.put(stop, rdr.get("fare_area_id").intern()); // intern for efficiency
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading subway transfers", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing subway transfers", e);
            }
        }
    }
}
