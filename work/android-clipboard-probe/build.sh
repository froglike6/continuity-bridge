#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PLATFORM_JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar
TOOLS_DIR=/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export JAVA_HOME
JAVAC="$JAVA_HOME/bin/javac"
ZIP=/usr/bin/zip
KEYSTORE=$HOME/.android/debug.keystore

AAPT2="$TOOLS_DIR/aapt2"
D8="$TOOLS_DIR/d8"
ZIPALIGN="$TOOLS_DIR/zipalign"
APKSIGNER="$TOOLS_DIR/apksigner"

SOURCE_DIR="$PROJECT_DIR/app/src/main/java"
RESOURCE_DIR="$PROJECT_DIR/app/src/main/res"
MANIFEST="$PROJECT_DIR/app/src/main/AndroidManifest.xml"
BUILD_DIR="$PROJECT_DIR/build"
GENERATED_DIR="$BUILD_DIR/generated"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
RESOURCE_ARCHIVE="$BUILD_DIR/resources.zip"
CLASS_ARCHIVE="$BUILD_DIR/classes.zip"
JAVA_SOURCES="$BUILD_DIR/java-sources.txt"
UNALIGNED_APK="$BUILD_DIR/app-unaligned.apk"
ALIGNED_APK="$BUILD_DIR/app-aligned.apk"
OUTPUT_APK="$BUILD_DIR/app-debug.apk"

for required in "$PLATFORM_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$JAVAC" "$ZIP" "$KEYSTORE" "$MANIFEST"; do
    if [ ! -e "$required" ]; then
        echo "Missing required local path: $required" >&2
        exit 1
    fi
done

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$GENERATED_DIR" "$CLASSES_DIR" "$DEX_DIR"

"$AAPT2" compile --dir "$RESOURCE_DIR" -o "$RESOURCE_ARCHIVE"
"$AAPT2" link \
    -I "$PLATFORM_JAR" \
    --manifest "$MANIFEST" \
    --java "$GENERATED_DIR" \
    --min-sdk-version 29 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 1.0 \
    -o "$UNALIGNED_APK" \
    "$RESOURCE_ARCHIVE"

find "$SOURCE_DIR" "$GENERATED_DIR" -type f -name '*.java' -print | LC_ALL=C sort > "$JAVA_SOURCES"
"$JAVAC" \
    -Xlint:all \
    -Xlint:-options \
    -encoding UTF-8 \
    -source 8 \
    -target 8 \
    -bootclasspath "$PLATFORM_JAR" \
    -d "$CLASSES_DIR" \
    @"$JAVA_SOURCES"

(
    cd "$CLASSES_DIR"
    "$ZIP" -X -q -r "$CLASS_ARCHIVE" .
)
"$D8" \
    --lib "$PLATFORM_JAR" \
    --min-api 29 \
    --output "$DEX_DIR" \
    "$CLASS_ARCHIVE"

(
    cd "$DEX_DIR"
    "$ZIP" -X -q "$UNALIGNED_APK" classes.dex
)
"$ZIPALIGN" -f -p 4 "$UNALIGNED_APK" "$ALIGNED_APK"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_APK" \
    "$ALIGNED_APK"

test -s "$DEX_DIR/classes.dex"
test -s "$OUTPUT_APK"
echo "Built $OUTPUT_APK"
