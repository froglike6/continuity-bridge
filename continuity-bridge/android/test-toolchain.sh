#!/bin/sh
set -eu
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_TEMP=$(mktemp -d "${TMPDIR:-/tmp}/continuity-toolchain.XXXXXX")
trap 'rm -rf "$TASK_TEMP"' EXIT INT TERM
JDK="$TASK_TEMP/jdk with spaces"
SDK="$TASK_TEMP/sdk with spaces"
mkdir -p "$JDK/bin" "$SDK/platforms/android-35" "$SDK/build-tools/35.0.0" "$SDK/cmdline-tools/latest/bin"
for tool in java javac javap keytool; do
    printf '#!/bin/sh\nexit 0\n' > "$JDK/bin/$tool"
    chmod +x "$JDK/bin/$tool"
done
for tool in aapt2 aidl d8 zipalign apksigner; do
    printf '#!/bin/sh\nexit 0\n' > "$SDK/build-tools/35.0.0/$tool"
    chmod +x "$SDK/build-tools/35.0.0/$tool"
done
touch "$SDK/platforms/android-35/android.jar" "$SDK/platforms/android-35/framework.aidl" "$SDK/build-tools/35.0.0/core-lambda-stubs.jar"
printf '#!/bin/sh\nexit 0\n' > "$SDK/cmdline-tools/latest/bin/apkanalyzer"
chmod +x "$SDK/cmdline-tools/latest/bin/apkanalyzer"

discover() {
    env -u ANDROID_HOME -u ANDROID_SDK_ROOT -u CONTINUITY_ANDROID_BUILD_TOOLS \
        -u CONTINUITY_ANDROID_PLATFORM_DIR -u CONTINUITY_APKANALYZER JAVA_HOME="$JDK" "$@" \
        sh -eu -c '. "$1"; continuity_android_tools; printf "%s\n%s\n%s\n" "$PLATFORM_JAR" "$AAPT2" "$APK_ANALYZER"' \
        sh "$ANDROID_DIR/toolchain.sh"
}
expected=$(printf '%s\n' "$SDK/platforms/android-35/android.jar" "$SDK/build-tools/35.0.0/aapt2" "$SDK/cmdline-tools/latest/bin/apkanalyzer")
test "$(discover ANDROID_HOME="$SDK")" = "$expected"
test "$(discover ANDROID_SDK_ROOT="$SDK")" = "$expected"
test "$(discover ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$TASK_TEMP/missing")" = "$expected"

mv "$SDK/build-tools/35.0.0" "$TASK_TEMP/tools35"
mv "$SDK/platforms/android-35" "$TASK_TEMP/platform35"
override_expected=$(printf '%s\n' "$TASK_TEMP/platform35/android.jar" "$TASK_TEMP/tools35/aapt2" "$SDK/cmdline-tools/latest/bin/apkanalyzer")
test "$(discover ANDROID_HOME="$SDK" CONTINUITY_ANDROID_BUILD_TOOLS="$TASK_TEMP/tools35" \
    CONTINUITY_ANDROID_PLATFORM_DIR="$TASK_TEMP/platform35")" = "$override_expected"

if discover ANDROID_HOME="$TASK_TEMP/missing" > "$TASK_TEMP/missing-sdk.log" 2>&1; then
    echo 'Missing SDK unexpectedly accepted' >&2; exit 1
fi
if discover ANDROID_HOME="$SDK" CONTINUITY_ANDROID_PLATFORM_DIR="$TASK_TEMP/platform35" \
    CONTINUITY_ANDROID_BUILD_TOOLS="$TASK_TEMP/tools35" CONTINUITY_APKANALYZER="$TASK_TEMP/missing" \
    > "$TASK_TEMP/missing-analyzer.log" 2>&1; then
    echo 'Missing APK analyzer unexpectedly accepted' >&2; exit 1
fi
echo 'TOOLCHAIN_TESTS_OK android_home=1 android_sdk_root=1 precedence=1 paths_with_spaces=1 unpacked_tools=1 missing_sdk=1 missing_analyzer=1'
