#!/bin/sh
set -eu
if test "${CONTINUITY_FIXTURE_BOUNDED:-0}" != 1; then
    exec env CONTINUITY_FIXTURE_BOUNDED=1 /usr/bin/perl -e 'alarm 45; exec @ARGV or die "exec failed: $!\n"' "$0" "$@"
fi
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$ANDROID_DIR/../.." && pwd)
PLATFORM_JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar
TOOLS=/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KEYSTORE=$HOME/.android/debug.keystore
BUILD="$ANDROID_DIR/build/fixture"
SOURCE="$ANDROID_DIR/fixture/src/main/java"
MANIFEST="$ANDROID_DIR/fixture/src/main/AndroidManifest.xml"
rm -rf "$BUILD"; mkdir -p "$BUILD/classes" "$BUILD/dex"
"$TOOLS/aapt2" link -I "$PLATFORM_JAR" --manifest "$MANIFEST" --min-sdk-version 29 --target-sdk-version 35 \
    --version-code 1 --version-name 1.0 -o "$BUILD/unsigned.apk"
find "$SOURCE" -name '*.java' -print | LC_ALL=C sort > "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -Xlint:all -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLATFORM_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$ANDROID_DIR/verify-fixture-surface.sh" --static "$SOURCE/com/froglike6/continuityfixture/FixtureActivity.java" "$BUILD/classes"
(cd "$BUILD/classes" && /usr/bin/zip -X -q -r "$BUILD/classes.zip" .)
"$TOOLS/d8" --lib "$PLATFORM_JAR" --min-api 29 --output "$BUILD/dex" "$BUILD/classes.zip"
(cd "$BUILD/dex" && /usr/bin/zip -X -q "$BUILD/unsigned.apk" classes.dex)
"$ANDROID_DIR/verify-adapter-boundary.sh" fixture "$ANDROID_DIR/fixture/src" "$BUILD/classes" "$BUILD/unsigned.apk"
"$TOOLS/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$BUILD/continuity-fixture-debug.apk" "$BUILD/aligned.apk"
mkdir -p "$ROOT/outputs"
OUTPUT_TMP="$ROOT/outputs/.continuity-fixture-debug.apk.new.$$"
trap 'rm -f "$OUTPUT_TMP"' EXIT INT TERM
cp "$BUILD/continuity-fixture-debug.apk" "$OUTPUT_TMP"
mv -f "$OUTPUT_TMP" "$ROOT/outputs/continuity-fixture-debug.apk"
trap - EXIT INT TERM
echo "FIXTURE_BUILD_OK apk=$ROOT/outputs/continuity-fixture-debug.apk"
