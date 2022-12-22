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
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.DebugRoutingVisitor;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.TurnRestriction;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.point_to_point.builder.RouterInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import gnu.trove.map.TIntIntMap;
import gnu.trove.set.TIntSet;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.PrecisionModel;
import com.vividsolutions.jts.operation.buffer.BufferParameters;
import com.vividsolutions.jts.operation.buffer.OffsetCurveBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;
import static spark.Spark.before;
import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.staticFileLocation;

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

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph.\n--build --save-shapes [path] will save the shapes in the feed";

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
                //transportNetwork.readOSM(new File(dir, "osm.mapdb"));
                run(transportNetwork);
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transit networks", e);
                System.exit(-1);
            }
        } else if ("--isochrones".equals(commandArguments[0])) {
            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }
            try {
                // LOG.info("Loading transit networks from: {}", dir);
                // TransportNetwork transportNetwork = TransportNetwork.read(new File(dir, "network.dat"));
                // transportNetwork.readOSM(new File(dir, "osm.mapdb"));
                // transportNetwork.transitLayer.buildDistanceTables(null);
                // // Build WALK and CAR linked pointsets because they are needed for isochrones (which are enabled).
                // transportNetwork.rebuildLinkedGridPointSet(StreetMode.WALK, StreetMode.CAR);
                // run(transportNetwork);
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
        ParetoServer paretoServer = new ParetoServer(transportNetwork);

        get("/metadata", (request, response) -> {
            response.header("Content-Type", "application/json");
            RouterInfo routerInfo = new RouterInfo();
            routerInfo.envelope = transportNetwork.getEnvelope();
            return routerInfo;

        }, JsonUtilities.objectMapper::writeValueAsString);

        post("/pareto", paretoServer::handle);
    }

    /**
     * Add a feature to the supplied List of GeoJSON features. Used in street layer debug visualizations.
     */
    private static void makeTurnEdge(TransportNetwork transportNetwork, boolean both,
        List<GeoJsonFeature> features, EdgeStore.Edge cursor, OffsetCurveBuilder offsetBuilder,
        float distance, int edgeIdx) {
        if (transportNetwork.streetLayer.edgeStore.turnRestrictions.containsKey(edgeIdx)) {

            final int numberOfRestrictions =
                    transportNetwork.streetLayer.edgeStore.turnRestrictions.get(edgeIdx).size();
            List<Integer> edge_restricion_idxs = new ArrayList<>(numberOfRestrictions);
            transportNetwork.streetLayer.edgeStore.turnRestrictions.get(edgeIdx)
                .forEach(turn_restriction_idx -> {
                    edge_restricion_idxs.add(turn_restriction_idx);
                    return true;
                });
            for (int i=0; i < edge_restricion_idxs.size(); i++) {
                int turnRestrictionIdx = edge_restricion_idxs.get(i);
                TurnRestriction turnRestriction = transportNetwork.streetLayer.turnRestrictions.get(turnRestrictionIdx);

                //TurnRestriction.fromEdge isn't necessary correct
                //If edge on which from is is splitted then fromEdge is different but isn't updated in TurnRestriction
                cursor.seek(edgeIdx);

                GeoJsonFeature feature = getEdgeFeature(both, cursor, offsetBuilder,
                    distance, transportNetwork);

                feature.addProperty("only", turnRestriction.only);
                feature.addProperty("edge", "FROM");
                feature.addProperty("restrictionId", turnRestrictionIdx);

                features.add(feature);

                if (turnRestriction.viaEdges.length > 0) {
                    for (int idx = 0; idx < turnRestriction.viaEdges.length; idx++) {
                        int via_edge_index = turnRestriction.viaEdges[idx];
                        cursor.seek(via_edge_index);

                        feature = getEdgeFeature(both, cursor, offsetBuilder,
                            distance, transportNetwork);

                        feature.addProperty("only", turnRestriction.only);
                        feature.addProperty("edge", "VIA");
                        feature.addProperty("via_edge_idx", idx);
                        feature.addProperty("restrictionId", turnRestrictionIdx);

                        features.add(feature);
                    }

                }
                cursor.seek(turnRestriction.toEdge);

                feature = getEdgeFeature(both, cursor, offsetBuilder, distance,
                    transportNetwork);

                feature.addProperty("only", turnRestriction.only);
                feature.addProperty("edge", "TO");
                feature.addProperty("restrictionId", turnRestrictionIdx);

                features.add(feature);
            }

        }
    }

    /**
     * Creates features from from and to vertices of provided edge
     * if they weren't already created and they have TRAFFIC_SIGNAL flag
     */
    private static void getVertexFeatures(EdgeStore.Edge cursor, VertexStore.Vertex vcursor,
        Set<Integer> seenVertices, List<GeoJsonFeature> features, TransportNetwork network) {

        int fromVertex = cursor.getFromVertex();
        if (!seenVertices.contains(fromVertex)) {
            vcursor.seek(fromVertex);
            GeoJsonFeature feature = getVertexFeature(vcursor, network);
            //It can be null since we only insert vertices with flags
            if (feature != null) {
                features.add(feature);
            }
            seenVertices.add(fromVertex);
        }
        int toVertex = cursor.getToVertex();
        if (!seenVertices.contains(toVertex)) {
            vcursor.seek(toVertex);
            GeoJsonFeature feature = getVertexFeature(vcursor, network);
            //It can be null since we only insert vertices with flags
            if (feature != null) {
                features.add(feature);
            }
            seenVertices.add(toVertex);
        }
    }

    /**
     * Creates geojson feature from specified vertex
     *
     * Currently it only does that if vertex have TRAFFIC_SIGNAL or BIKE_SHARING flag.
     * Properties in GeoJSON are:
     * - vertex_id
     * - flags: TRAFFIC_SIGNAL or BIKE_SHARING currently not both
     * @param vertex
     * @return
     */
    private static GeoJsonFeature getVertexFeature(VertexStore.Vertex vertex, TransportNetwork network) {
        GeoJsonFeature feature = null;
        if (network.transitLayer.stopForStreetVertex.containsKey(vertex.index)) {
            // jitter transit stops slightly, in a deterministic way, so we can see if they're linked correctly
            feature = new GeoJsonFeature(GeometryUtils.geometryFactory.createPoint(jitter(vertex)));
            //Used for showing stop vertices in debug client
            feature.addProperty("STOP", true);
        } else {
            feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
        }

        feature.addProperty("vertex_id", vertex.index);
        //Needed for filtering flags
        for (VertexStore.VertexFlag flag: VertexStore.VertexFlag.values()) {
            if (vertex.getFlag(flag)) {
                feature.addProperty(flag.toString(), true);
            }
        }
        //feature.addProperty("flags", cursor.getFlagsAsString());

        return feature;
    }

    /**
     * Jitter the location of a vertex in a deterministic way.
     * Used to displace transit stops from the vertices they are linked to, so we can see the linking structure of
     * complex stops.
     */
    public static Coordinate jitter (VertexStore.Vertex v) {
        double lat = v.getLat();
        lat += (v.index % 7 - 3.5) * 1e-5;
        double lon = v.getLon();
        lon += (v.index % 11 - 5.5) * 1e-5;
        return new Coordinate(lon, lat);
    }


    private static void fillFeature(TransportNetwork transportNetwork, StreetRouter.State lastState,
        List<GeoJsonFeature> features, boolean reverse) {

        StreetPath streetPath = new StreetPath(lastState, transportNetwork, reverse);

        int stateIdx = 0;

        //TODO: this can be improved since end and start vertices are the same in all the edges.
        for (StreetRouter.State state : streetPath.getStates()) {
            Integer edgeIdx = state.backEdge;
            if (!(edgeIdx == -1 || edgeIdx == null)) {
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore
                    .getCursor(edgeIdx);
                GeoJsonFeature feature = new GeoJsonFeature(edge.getGeometry());
                feature.addProperty("mode", state.streetMode);
                feature.addProperty("distance", state.distance/1000);
                feature.addProperty("idx", stateIdx++);
                feature.addProperty("stateIdx", state.idx);
                features.add(feature);
                feature.addProperty("edgeIdx", edgeIdx);
            }
        }
    }

    /**
     * Gets feature from edge in EdgeStore as GeoJSON for debug visualization of the street layer.
     * @param both true if we are showing edges in both directions AKA it needs to be offset
     * @param cursor cursor to current forward or reversed edge
     * @param offsetBuilder builder which creates edge offset if needed
     * @param distance for which edge is offset if both is true
     */
    private static GeoJsonFeature getEdgeFeature(boolean both, EdgeStore.Edge cursor,
        OffsetCurveBuilder offsetBuilder, float distance, TransportNetwork network) {
        LineString geometry = cursor.getGeometry();
        Coordinate[] coords = geometry.getCoordinates();

        if (both) {
            coords = offsetBuilder.getOffsetCurve(coords,
                distance);
        }


        if (network.transitLayer.stopForStreetVertex.containsKey(cursor.getFromVertex())) {
            // from vertex is a transit stop, jitter it so that it doesn't sit exactly on top of the street vertex
            // and so that we can see when multiple stops get linked to the same place
            VertexStore.Vertex v = network.streetLayer.vertexStore.getCursor(cursor.getFromVertex());
            coords[0] = jitter(v);
        }

        if (network.transitLayer.stopForStreetVertex.containsKey(cursor.getToVertex())) {
            VertexStore.Vertex v = network.streetLayer.vertexStore.getCursor(cursor.getToVertex());
            coords[coords.length - 1] = jitter(v);
        }
        geometry = GeometryUtils.geometryFactory.createLineString(coords);

        GeoJsonFeature feature = new GeoJsonFeature(geometry);
        feature.addProperty("permission", cursor.getPermissionsAsString());
        feature.addProperty("edge_id", cursor.getEdgeIndex());
        feature.addProperty("speed_ms", cursor.getSpeed());
        feature.addProperty("osmid", cursor.getOSMID());
        //Needed for filtering flags
        for (EdgeStore.EdgeFlag flag: EdgeStore.EdgeFlag.values()) {
            if (cursor.getFlag(flag)) {
                feature.addProperty(flag.toString(), true);
            }
        }
        feature.addProperty("flags", cursor.getFlagsAsString());
        return feature;
    }

    private static void updateSpeed(EdgeStore.Edge edge, Map<Short, Integer> speedUsage,
        MinMax minMax) {
        Short currentEdgeSpeed = edge.getSpeed();
        Integer currentValue = speedUsage.getOrDefault(currentEdgeSpeed, 0);
        speedUsage.put(currentEdgeSpeed, currentValue+1);
        minMax.updateMin(currentEdgeSpeed);
        minMax.updateMax(currentEdgeSpeed);
    }

    private static class MinMax {
        public short min = Short.MAX_VALUE;
        public short max = Short.MIN_VALUE;

        public void updateMin(Short currentEdgeSpeed) {
            min = (short) Math.min(currentEdgeSpeed, min);
        }

        public void updateMax(Short currentEdgeSpeed) {
            max = (short) Math.max(currentEdgeSpeed, max);
        }
    }

    private static float roundSpeed(float speed) {
        return Math.round(speed * 1000) / 1000;
    }

}
