#!/bin/bash

OSM=~/git/charlie-containers/new/boston.pbf
GTFS=~/git/charlie-containers/new/baseline.zip

# build R5
if [ -e pom.xml ]; then
    # build with Maven
    mvn package -DskipTests
else
    gradle shadowJar -x test
    R5=$(echo build/libs/r5-*-all.jar)
fi

java -cp "$R5" com.conveyal.r5.analyst.fare.FareBisect "$OSM" "$GTFS"

exit $?