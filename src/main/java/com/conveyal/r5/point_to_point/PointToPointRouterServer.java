package com.conveyal.r5.point_to_point;

import com.conveyal.r5.analyst.fare.ParetoServer;
import com.conveyal.r5.api.GraphQlRequest;
import com.conveyal.r5.api.util.BikeRentalStation;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.api.util.Stop;
import com.conveyal.r5.common.GeoJsonFeature;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.point_to_point.builder.RouterInfo;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.*;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.operation.buffer.BufferParameters;
import com.vividsolutions.jts.operation.buffer.OffsetCurveBuilder;
import gnu.trove.map.TIntIntMap;
import gnu.trove.set.TIntSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import java.io.File;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;
import static spark.Spark.*;

/**
 * This will represent point to point search server.
 *
 * It can build point to point TransportNetwork and start a server with API for point to point searches
 *
 */
public class PointToPointRouterServer {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointRouterServer.class);

    private static final int DEFAULT_PORT = 8080;

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph";

    public static final int RADIUS_METERS = 200;

    public static void main(String[] commandArguments) {

        LOG.info("Arguments: {}", Arrays.toString(commandArguments));

        final boolean inMemory = false;

        if ("--build".equals(commandArguments[0])) {

            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }

            TransportNetwork transportNetwork = TransportNetwork.fromDirectory(dir);
            //In memory doesn't save it to disk others do (build, preFlight)
            if (!inMemory) {
                try {
                    transportNetwork.write(new File(dir, "network.dat"));
                } catch (Exception e) {
                    LOG.error("An error occurred during saving transit networks. Exiting.", e);
                    System.exit(-1);
                }
            }
        } else if ("--graphs".equals(commandArguments[0])) {
            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }
            try {
                LOG.info("Loading transit networks from: {}", dir);
                TransportNetwork transportNetwork = TransportNetwork.read(new File(dir, "network.dat"));
                transportNetwork.readOSM(new File(dir, "osm.mapdb"));
                run(transportNetwork);
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transit networks", e);
                System.exit(-1);
            }
        } else if ("--help".equals(commandArguments[0])
                || "-h".equals(commandArguments[0])
                || "--usage".equals(commandArguments[0])
                || "-u".equals(commandArguments[0])) {
            System.out.println(USAGE);
        } else {
            LOG.info("Unknown argument: {}", commandArguments[0]);
            System.out.println(USAGE);
        }

    }

    private static void run(TransportNetwork transportNetwork) {
        port(DEFAULT_PORT);
        ObjectMapper mapper = new ObjectMapper();
        //ObjectReader is a new lightweight mapper which can only deserialize specified class
        ObjectReader graphQlRequestReader = mapper.reader(GraphQlRequest.class);
        ObjectReader mapReader = mapper.reader(HashMap.class);
        staticFileLocation("debug-plan");
        PointToPointQuery pointToPointQuery = new PointToPointQuery(transportNetwork);
        ParetoServer paretoServer = new ParetoServer(transportNetwork);
        get("/pareto", paretoServer::handle);
    }
}