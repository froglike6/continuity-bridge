#!/bin/sh
set -eu
if test "${CONTINUITY_TOKEN_TEST_BOUNDED:-0}" != 1; then
    exec env CONTINUITY_TOKEN_TEST_BOUNDED=1 /usr/bin/perl -e 'alarm 45; exec @ARGV or die "exec failed: $!\n"' "$0" "$@"
fi
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_android_tools
continuity_debug_keystore
TEST_RUNNER_JAR="$PLATFORM_DIR/optional/android.test.runner.jar"
TEST_BASE_JAR="$PLATFORM_DIR/optional/android.test.base.jar"
TOOLS="$TOOLS_DIR"
for path in "$TEST_RUNNER_JAR" "$TEST_BASE_JAR"; do
    test -f "$path" || continuity_tool_error "Missing Android test library: $path"
done
BUILD="$ANDROID_DIR/build/tokenstore-test"
TEST="$ANDROID_DIR/instrumentation-test"
PRODUCTION_BUILD="$ANDROID_DIR/build/shizuku"
test -d "$PRODUCTION_BUILD/classes" || { echo 'Run android/build.sh to build production classes first' >&2; exit 1; }
DEPENDENCY_CP=$(find "$PRODUCTION_BUILD/dependencies/jars" -name '*.jar' -print | LC_ALL=C sort | paste -sd ':' -)
find "$BUILD" -type f -delete 2>/dev/null || true
find "$BUILD" -depth -type d -empty -delete 2>/dev/null || true
mkdir -p "$BUILD/classes" "$BUILD/dex"
find "$TEST" -name '*.java' -print | LC_ALL=C sort > "$BUILD/sources.txt"
"$TOOLS/aapt2" link -I "$PLATFORM_JAR" --manifest "$TEST/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 35 -o "$BUILD/test-unsigned.apk"
"$JAVA_HOME/bin/javac" -Xlint:all -Xlint:-deprecation -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$PLATFORM_JAR" -classpath "$PRODUCTION_BUILD/classes:$DEPENDENCY_CP:$TEST_RUNNER_JAR:$TEST_BASE_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
(cd "$BUILD/classes" && /usr/bin/zip -X -q -r "$BUILD/classes.zip" .)
"$TOOLS/d8" --lib "$PLATFORM_JAR" --min-api 29 --output "$BUILD/dex" "$BUILD/classes.zip"
(cd "$BUILD/dex" && /usr/bin/zip -X -q "$BUILD/test-unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f -p 4 "$BUILD/test-unsigned.apk" "$BUILD/test-aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out "$BUILD/tokenstore-instrumentation.apk" "$BUILD/test-aligned.apk"
test -s "$BUILD/tokenstore-instrumentation.apk"
echo "TOKENSTORE_TEST_BUILD_OK apk=$BUILD/tokenstore-instrumentation.apk"
