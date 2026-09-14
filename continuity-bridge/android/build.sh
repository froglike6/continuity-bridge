#!/bin/sh
set -eu
if test "${CONTINUITY_BUILD_BOUNDED:-0}" != 1; then
    exec env CONTINUITY_BUILD_BOUNDED=1 /usr/bin/perl -e 'alarm 180; exec @ARGV or die "exec failed: $!\n"' "$0" "$@"
fi
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$ANDROID_DIR/../.." && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_android_tools
continuity_debug_keystore
BUILD="$ANDROID_DIR/build/shizuku"
SOURCE="$ANDROID_DIR/app/src/main/java"
RESOURCES="$ANDROID_DIR/app/src/main/res"
CANONICAL_CA="${CONTINUITY_ANDROID_CA_PEM:-$RESOURCES/raw/continuity_local_ca.pem}"
MANIFEST="$ANDROID_DIR/app/src/main/AndroidManifest.xml"
for path in "$PLATFORM_JAR" "$TOOLS_DIR/aapt2" "$TOOLS_DIR/aidl" "$TOOLS_DIR/d8" "$TOOLS_DIR/zipalign" "$TOOLS_DIR/apksigner" "$JAVA_HOME/bin/javac" "$KEYSTORE" "$MANIFEST" "$CANONICAL_CA"; do
    test -e "$path" || { echo "Missing local tool: $path" >&2; exit 1; }
done
if test -z "${CONTINUITY_ANDROID_CA_PEM:-}"; then
    CA_SHA256=$(shasum -a 256 "$CANONICAL_CA" | awk '{print $1}')
    test "$CA_SHA256" = c6cfdabcf2ca0774883c80e23277360fb44e7430ddec3f99bb1925e9975ca86b || {
        echo 'Android public test CA changed; set CONTINUITY_ANDROID_CA_PEM to your matching local CA explicitly.' >&2
        exit 1
    }
elif ! openssl x509 -in "$CANONICAL_CA" -outform PEM 2>/dev/null | cmp -s - "$CANONICAL_CA" || \
    ! openssl x509 -in "$CANONICAL_CA" -noout -text 2>/dev/null | grep -Fq 'CA:TRUE' || \
    ! openssl verify -CAfile "$CANONICAL_CA" "$CANONICAL_CA" >/dev/null 2>&1; then
    echo 'CONTINUITY_ANDROID_CA_PEM must contain only a currently valid public PEM CA certificate.' >&2
    exit 1
fi
cmp -s "$CANONICAL_CA" "$RESOURCES/raw/continuity_local_ca.pem" || {
    echo "Android CA resource differs from CONTINUITY_ANDROID_CA_PEM" >&2
    exit 1
}
rm -rf "$BUILD"
mkdir -p "$BUILD/generated" "$BUILD/classes" "$BUILD/dex"
"$ANDROID_DIR/dependencies/embedded-fetch.sh" "$BUILD/dependencies"
DEPENDENCY_CP=$(find "$BUILD/dependencies/jars" -name '*.jar' -print | LC_ALL=C sort | paste -sd ':' -)
for aidl in "$ANDROID_DIR"/app/src/main/aidl/com/froglike6/continuitybridge/*.aidl; do
    "$TOOLS_DIR/aidl" -p"$(dirname "$PLATFORM_JAR")/framework.aidl" -I"$ANDROID_DIR/app/src/main/aidl" \
        -o"$BUILD/generated" "$aidl"
done
"$TOOLS_DIR/aapt2" compile --dir "$RESOURCES" -o "$BUILD/resources.zip"
"$TOOLS_DIR/aapt2" link -I "$PLATFORM_JAR" --manifest "$MANIFEST" --java "$BUILD/generated" \
    --min-sdk-version 29 --target-sdk-version 35 --version-code 2 --version-name 1.1 \
    --debug-mode \
    -o "$BUILD/app-unsigned.apk" "$BUILD/resources.zip"
find "$SOURCE" "$BUILD/generated" -name '*.java' -print | LC_ALL=C sort > "$BUILD/sources.txt"
find "$ANDROID_DIR/dependencies/patches" -name '*.java' -print | LC_ALL=C sort >> "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -Xlint:all -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$PLATFORM_JAR:$TOOLS_DIR/core-lambda-stubs.jar" -classpath "$DEPENDENCY_CP" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$ANDROID_DIR/verify-production-wiring.sh" "$BUILD/classes"
"$ANDROID_DIR/verify-manifest-source.sh" "$MANIFEST"
(cd "$BUILD/classes" && /usr/bin/zip -X -q -r "$BUILD/classes.zip" .)
"$TOOLS_DIR/d8" --lib "$PLATFORM_JAR" --classpath "$BUILD/dependencies/jars/annotation-1.3.0.jar" \
    --min-api 29 --output "$BUILD/dex" "$BUILD/classes.zip" \
    "$BUILD/dependencies/jars/libadb-android-3.1.1.jar" "$BUILD/dependencies/jars/spake2-android-2.2.1.jar" \
    "$BUILD/dependencies/jars/bcprov-jdk15to18-1.81.jar" "$BUILD/dependencies/jars/conscrypt-android-2.7.0.jar"
(cd "$BUILD/dex" && /usr/bin/zip -X -q "$BUILD/app-unsigned.apk" classes*.dex)
(cd "$BUILD/dependencies" && /usr/bin/zip -X -q -r "$BUILD/app-unsigned.apk" lib assets)
(cd "$BUILD/dependencies/resources" && /usr/bin/zip -X -q -r "$BUILD/app-unsigned.apk" .)
"$ANDROID_DIR/verify-adapter-boundary.sh" production "$ANDROID_DIR/app/src/main" "$BUILD/classes" "$BUILD/app-unsigned.apk"
"$TOOLS_DIR/zipalign" -P 16 -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
"$TOOLS_DIR/zipalign" -c -P 16 4 "$BUILD/app-aligned.apk"
"$TOOLS_DIR/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out "$BUILD/continuity-bridge-debug.apk" "$BUILD/app-aligned.apk"
test -s "$BUILD/continuity-bridge-debug.apk"
mkdir -p "$ROOT/outputs"
OUTPUT_APK="$ROOT/outputs/continuity-bridge-android-embedded-debug.apk"
OUTPUT_RECEIPT="$OUTPUT_APK.sha256"
OUTPUT_APK_TMP="$ROOT/outputs/.continuity-bridge-android-embedded-debug.apk.new.$$"
OUTPUT_RECEIPT_TMP="$ROOT/outputs/.continuity-bridge-android-embedded-debug.apk.sha256.new.$$"
trap 'rm -f "$OUTPUT_APK_TMP" "$OUTPUT_RECEIPT_TMP"' EXIT INT TERM
cp "$BUILD/continuity-bridge-debug.apk" "$OUTPUT_APK_TMP"
APK_SHA256=$(shasum -a 256 "$OUTPUT_APK_TMP" | awk '{print $1}')
printf '%s  %s\n' "$APK_SHA256" "continuity-bridge-android-embedded-debug.apk" > "$OUTPUT_RECEIPT_TMP"
mv -f "$OUTPUT_APK_TMP" "$OUTPUT_APK"
mv -f "$OUTPUT_RECEIPT_TMP" "$OUTPUT_RECEIPT"
(CDPATH= cd -- "$ROOT/outputs" && shasum -a 256 -c "$(basename "$OUTPUT_RECEIPT")")
trap - EXIT INT TERM
echo "ANDROID_BUILD_OK apk=$OUTPUT_APK sha256=$APK_SHA256"
