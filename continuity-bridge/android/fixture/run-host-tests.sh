#!/bin/sh
set -eu

FIXTURE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$FIXTURE_DIR/../build/fixture-host-test"
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
PLATFORM_JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar
SOURCE="$FIXTURE_DIR/src/main/java/com/froglike6/continuityfixture/FixtureActivity.java"
TEST="$FIXTURE_DIR/host-test/com/froglike6/continuityfixture/PipelineObservationTest.java"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
"$JAVA_HOME/bin/javac" -Xlint:all -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$PLATFORM_JAR" -d "$BUILD/classes" "$SOURCE" "$TEST"
"$JAVA_HOME/bin/java" -cp "$BUILD/classes:$PLATFORM_JAR" com.froglike6.continuityfixture.PipelineObservationTest
RECEIPT_DUMP=$("$JAVA_HOME/bin/javap" -classpath "$BUILD/classes" -c -p \
    'com.froglike6.continuityfixture.FixtureActivity$PreferenceReceiptSink')
printf '%s\n' "$RECEIPT_DUMP" | grep -Fq 'android/util/Log.i'
printf '%s\n' 'FIXTURE_PIPELINE_RECEIPT_SURFACE_OK metadata=observation_id+phase payload=false token=false'
