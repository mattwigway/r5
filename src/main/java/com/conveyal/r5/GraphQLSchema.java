package com.conveyal.r5;

import com.conveyal.r5.api.util.*;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.model.json_serialization.PolyUtil;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.fare.RideType;
import com.fasterxml.jackson.core.JsonProcessingException;
import graphql.GraphQLException;
import graphql.Scalars;
import graphql.language.StringValue;
import graphql.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Created by mabu on 30.10.2015.
 */
public class GraphQLSchema {
    private static final Logger LOG = LoggerFactory.getLogger(GraphQLSchema.class);

    public static GraphQLEnumType locationTypeEnum = GraphQLEnumType.newEnum()
        .name("LocationType")
        .description("Identifies whether this stop represents a stop or station.")
        .value("STOP", 0, "A location where passengers board or disembark from a transit vehicle.")
        .value("STATION", 1, "A physical structure or area that contains one or more stop.")
        .value("ENTRANCE", 2)
        .build();

    public static GraphQLEnumType wheelchairBoardingEnum = GraphQLEnumType.newEnum()
        .name("WheelchairBoarding")
        .value("NO_INFORMATION", 0, "There is no accessibility information for the stop.")
        .value("POSSIBLE", 1, "At least some vehicles at this stop can be boarded by a rider in a wheelchair.")
        .value("NOT_POSSIBLE", 2, "Wheelchair boarding is not possible at this stop.")
        .build();

    public static GraphQLEnumType bikesAllowedEnum = GraphQLEnumType.newEnum()
        .name("BikesAllowed")
        .value("NO_INFORMATION", 0, "There is no bike information for the trip.")
        .value("ALLOWED", 1, "The vehicle being used on this particular trip can accommodate at least one bicycle.")
        .value("NOT_ALLOWED", 2, "No bicycles are allowed on this trip.")
        .build();

    public static GraphQLEnumType relativeDirectionEnum = GraphQLEnumType.newEnum()
        .name("RelativeDirection")
        .description("Represents a turn direction, relative to the current heading.")
        .value("DEPART", RelativeDirection.DEPART)
        .value("HARD_LEFT", RelativeDirection.HARD_LEFT)
        .value("LEFT", RelativeDirection.LEFT)
        .value("SLIGHTLY_LEFT", RelativeDirection.SLIGHTLY_LEFT)
        .value("CONTINUE", RelativeDirection.CONTINUE)
        .value("SLIGHTLY_RIGHT", RelativeDirection.SLIGHTLY_RIGHT)
        .value("RIGHT", RelativeDirection.RIGHT)
        .value("HARD_RIGHT", RelativeDirection.HARD_RIGHT)
        .value("CIRCLE_CLOCKWISE", RelativeDirection.CIRCLE_CLOCKWISE, "traffic circle in left driving countries")
        .value("CIRCLE_COUNTERCLOCKWISE", RelativeDirection.CIRCLE_COUNTERCLOCKWISE, "traffic circle in right driving countries")
        .value("ELEVATOR", RelativeDirection.ELEVATOR)
        .value("UTURN_LEFT", RelativeDirection.UTURN_LEFT)
        .value("UTURN_RIGHT", RelativeDirection.UTURN_RIGHT)
        .build();

    public static GraphQLEnumType absoluteDirectionEnum = GraphQLEnumType.newEnum()
        .name("AbsoluteDirection")
        .description("An absolute cardinal or intermediate direction.")
        .value("NORTH", AbsoluteDirection.NORTH)
        .value("NORTHEAST", AbsoluteDirection.NORTHEAST)
        .value("EAST", AbsoluteDirection.EAST)
        .value("SOUTHEAST", AbsoluteDirection.SOUTHEAST)
        .value("SOUTH", AbsoluteDirection.SOUTH)
        .value("SOUTHWEST", AbsoluteDirection.SOUTHWEST)
        .value("WEST", AbsoluteDirection.WEST)
        .value("NORTHWEST", AbsoluteDirection.NORTHWEST)
        .build();

    public static GraphQLEnumType nonTransitModeEnum = GraphQLEnumType.newEnum()
        .name("NonTransitMode")
        .description("Modes of transportation that aren't public transit")
        .value("WALK", NonTransitMode.WALK)
        .value("BICYCLE", NonTransitMode.BICYCLE)
        .value("CAR", NonTransitMode.CAR)
        .build();

    //This is used in streetSegments and is an union of accessLegModeEnum and otherLegModeEnum
    public static GraphQLEnumType legModeEnum = GraphQLEnumType.newEnum()
        .name("LegMode")
        .description("Modes of transport on ingress egress legs")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .value("CAR_PARK", LegMode.CAR_PARK, "Park & Ride")
        .build();

    //LegMode enum is splitted into multiple GraphQL legEnums so that we can get validation on input by GraphQL for free
    //Because some modes can appear only on access leg (CAR_PARK, BIKE_PARK), and some on egress and direct
    public static GraphQLEnumType accessLegModeEnum = GraphQLEnumType.newEnum()
        .name("AccessLegMode")
        .description("Modes of transport on ingress legs")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .value("CAR_PARK", LegMode.CAR_PARK, "Park & Ride")
        .build();

    public static GraphQLEnumType otherLegModeEnum = GraphQLEnumType.newEnum()
        .name("OtherLegMode")
        .description("Modes of transport on egress legs and directModes")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .build();

    public static GraphQLEnumType transitmodeEnum = GraphQLEnumType.newEnum()
        .name("TransitModes")
        .description("Types of transit mode transport from GTFS")
        .value("TRAM", TransitModes.TRAM,
            " Tram, Streetcar, Light rail. Any light rail or street level system within a metropolitan area.")
        .value("SUBWAY", TransitModes.SUBWAY,
            "Subway, Metro. Any underground rail system within a metropolitan area.")
        .value("RAIL", TransitModes.RAIL, "Rail. Used for intercity or long-distance travel.")
        .value("BUS", TransitModes.BUS, "Bus. Used for short- and long-distance bus routes.")
        .value("FERRY", TransitModes.FERRY,
            "Ferry. Used for short- and long-distance boat service.")
        .value("CABLE_CAR", TransitModes.CABLE_CAR, "Cable car. Used for street-level cable cars where the cable runs beneath the car.")
        .value("GONDOLA", TransitModes.GONDOLA, " Gondola, Suspended cable car. Typically used for aerial cable cars where the car is suspended from the cable.")
        .value("FUNICULAR", TransitModes.FUNICULAR, "Funicular. Any rail system designed for steep inclines.")
        .value("TRANSIT", TransitModes.TRANSIT, "All transit modes")
        .build();

    public static GraphQLEnumType searchTypeEnum = GraphQLEnumType.newEnum()
        .name("SearchType")
        .description("Type of plan search")
        .value("ARRIVE_BY", SearchType.ARRIVE_BY,
            "Search is made for trip that needs to arrive at specific time/date")
        .value("DEPART_FROM", SearchType.DEPART_FROM,
            "Search is made for a trip that needs to depart at specific time/date")
        .build();

    public static GraphQLEnumType rideTypeEnum = GraphQLEnumType.newEnum()
        .name("RideType")
        .description("Type of Ride in Fare. (Currently only DC Metro)")
        .value("METRO_RAIL", RideType.METRO_RAIL)
        .value("METRO_BUS_LOCAL", RideType.METRO_BUS_LOCAL)
        .value("METRO_BUS_EXPRESS", RideType.METRO_BUS_EXPRESS)
        .value("METRO_BUS_AIRPORT", RideType.METRO_BUS_AIRPORT)
        .value("DC_CIRCULATOR_BUS", RideType.DC_CIRCULATOR_BUS)
        .value("ART_BUS", RideType.ART_BUS)
        .value("DASH_BUS", RideType.DASH_BUS)
        .value("MARC_RAIL", RideType.MARC_RAIL)
        .value("MTA_BUS_LOCAL", RideType.MTA_BUS_LOCAL)
        .value("MTA_BUS_EXPRESS", RideType.MTA_BUS_EXPRESS)
        .value("MTA_BUS_COMMUTER", RideType.MTA_BUS_COMMUTER)
        .value("VRE_RAIL", RideType.VRE_RAIL)
        .value("MCRO_BUS_LOCAL", RideType.MCRO_BUS_LOCAL)
        .value("MCRO_BUS_EXPRESS", RideType.MCRO_BUS_EXPRESS)
        .value("FAIRFAX_CONNECTOR_BUS", RideType.FAIRFAX_CONNECTOR_BUS)
        .value("PRTC_BUS", RideType.PRTC_BUS)
        .build();


    //Input only for now
    public static GraphQLScalarType GraphQLLocalDate = new GraphQLScalarType("LocalDate",
        "Java 8 LocalDate type YYYY-MM-DD", new Coercing() {
        @Override
        public Object serialize(Object input) {
            LOG.info("Date coerce:{}", input);
            if (input instanceof String) {
                try {
                    if (input.equals("today")) {
                        return LocalDate.now();
                    }
                    return LocalDate
                        .parse((String) input, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (Exception e) {
                    throw new GraphQLException("Problem parsing date (Expected format is YYYY-MM-DD): " + e.getMessage());
                }
            } else  if (input instanceof LocalDate) {
                return input;
            } else {
                throw new GraphQLException("Invalid date input. Expected String or LocalDate");
            }
        }

        @Override
        public Object parseValue(Object input) {
            return serialize(input);
        }

        @Override
        public Object parseLiteral(Object input) {
            //Seems to be used in querying
            LOG.info("Date coerce literal:{}", input);
            if (!(input instanceof StringValue)) {
                return null;
            }
            try {
                String sinput = ((StringValue) input).getValue();
                if (sinput.equals("today")) {
                    return LocalDate.now();
                }
                return LocalDate
                    .parse(sinput, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                throw new GraphQLException("Problem parsing date (Expected format is YYYY-MM-DD): " + e.getMessage());
            }
        }
    });

    //Output type for now
    //FIXME: ISO8601 parsing and outputting in java is little broken it doesn't support timezone as +HH+MM
    //https://stackoverflow.com/questions/32079459/java-time-zoneddatetime-parse-and-iso8601
    public static GraphQLScalarType GraphQLZonedDateTime = new GraphQLScalarType("ZonedDateTime",
        "Java 8 ZonedDateTime type ISO 8601 YYYY-MM-DDTHH:MM:SS+HH:MM", new Coercing() {
        @Override
        public Object serialize(Object input) {
            //LOG.info("TDate coerce:{}", input);
            if (input instanceof String) {
                try {

                    return ZonedDateTime
                        .parse((String) input, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (Exception e) {
                    throw new GraphQLException("Problem parsing date (Expected format is ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM): " + e.getMessage());
                }
            } else  if (input instanceof ZonedDateTime) {
                return ((ZonedDateTime) input).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } else {
                throw new GraphQLException("Invalid date input. Expected String or LocalDate");
            }
        }

        @Override
        public Object parseValue(Object input) {
            return serialize(input);
        }

        @Override
        public Object parseLiteral(Object input) {
            //Seems to be used in querying
            //LOG.info("TDate coerce literal:{}", input);
            if (!(input instanceof StringValue)) {
                return null;
            }
            try {
                String sinput = ((StringValue) input).getValue();

                return ZonedDateTime
                    .parse(sinput, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception e) {
                throw new GraphQLException("Problem parsing date (Expected format is ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM): " + e.getMessage());
            }
        }
    });


    public GraphQLOutputType profileResponseType = new GraphQLTypeReference("Profile");

    public GraphQLOutputType profileOptionType = new GraphQLTypeReference("ProfileOption");

    public GraphQLOutputType routeType = new GraphQLTypeReference("Route");

    public GraphQLOutputType stopType = new GraphQLTypeReference("Stop");

    public GraphQLOutputType stopClusterType = new GraphQLTypeReference("StopCluster");

    public GraphQLOutputType fareType = new GraphQLTypeReference("Fare");

    public GraphQLOutputType statsType = new GraphQLTypeReference("Stats");

    public GraphQLOutputType polylineGeometryType = new GraphQLTypeReference("PolylineGeometry");

    public GraphQLOutputType streetEdgeInfoType = new GraphQLTypeReference("StreetEdgeInfo");

    public GraphQLOutputType streetSegmentType = new GraphQLTypeReference("StreetSegment");

    public GraphQLOutputType transitSegmentType = new GraphQLTypeReference("TransitSegment");

    public GraphQLOutputType segmentPatternType = new GraphQLTypeReference("SegmentPattern");

    public GraphQLOutputType bikeRentalStationType = new GraphQLTypeReference("BikeRentalStation");

    public GraphQLOutputType elevationType = new GraphQLTypeReference("Elevation");

    public GraphQLOutputType alertType = new GraphQLTypeReference("Alert");

    public GraphQLOutputType transitJourneyIDType = new GraphQLTypeReference("TransitJourneyID");

    public GraphQLOutputType pointToPointConnectionType = new GraphQLTypeReference("PointToPointConnection");

    public GraphQLOutputType itineraryType = new GraphQLTypeReference("Itinerary");

    public GraphQLOutputType tripPatternType = new GraphQLTypeReference("TripPattern");

    public GraphQLOutputType tripType = new GraphQLTypeReference("Trip");

    public GraphQLOutputType parkRideParkingType = new GraphQLTypeReference("ParkRideParking");

    // @formatter:off


    public GraphQLObjectType queryType;

    public graphql.schema.GraphQLSchema indexSchema;

    private GraphQLArgument stringTemplate(String name, String defaultValue) {
        GraphQLArgument.Builder argument = GraphQLArgument.newArgument().name(name).type(
            Scalars.GraphQLString);
        if (defaultValue != null) {
            argument.defaultValue(defaultValue);
        }
        return argument.build();
    }

    //TODO: code generation with:
    // http://sculptorgenerator.org/
    //https://github.com/square/javapoet
    //https://github.com/javaparser/javaparser https://github.com/musiKk/plyj
    //spark

}
