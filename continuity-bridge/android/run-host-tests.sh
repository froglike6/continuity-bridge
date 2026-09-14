#!/bin/sh
set -eu
if test "${CONTINUITY_HOST_BOUNDED:-0}" != 1; then
    exec env CONTINUITY_HOST_BOUNDED=1 /usr/bin/perl -e 'alarm 45; exec @ARGV or die "exec failed: $!\n"' "$0" "$@"
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANDROID_DIR="$ROOT/continuity-bridge/android"
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
PLATFORM_JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar
BUILD="$ANDROID_DIR/build/host"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
find "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/core" "$ANDROID_DIR/host-test" -name '*.java' -print > "$BUILD/sources.txt"
printf '%s\n' "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/ConnectionStatus.java" "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/UiStatePolicy.java" "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/ConfigStore.java" "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/MetadataLog.java" "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/RelayTransport.java" >> "$BUILD/sources.txt"
LC_ALL=C sort -u "$BUILD/sources.txt" -o "$BUILD/sources.txt"
printf '%s\n' "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/TokenStore.java" >> "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -encoding UTF-8 -classpath "$PLATFORM_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$JAVA_HOME/bin/java" -ea -cp "$BUILD/classes" com.froglike6.continuitybridge.HostSuite "$ROOT/continuity-bridge/protocol/fixtures" "$ROOT/continuity-bridge/runtime/tls"
"$JAVA_HOME/bin/java" -ea -cp "$BUILD/classes" com.froglike6.continuitybridge.AdapterHostSuite
"$JAVA_HOME/bin/java" -ea -cp "$BUILD/classes" com.froglike6.continuitybridge.ImageClipboardSuite
"$JAVA_HOME/bin/java" -ea -cp "$BUILD/classes" com.froglike6.continuitybridge.NotificationMappingSuite
"$JAVA_HOME/bin/java" -ea -cp "$BUILD/classes" com.froglike6.continuitybridge.RichProtocolSuite
STATE_TMP=$(mktemp -d "${TMPDIR:-/tmp}/continuity-state-test.XXXXXX")
trap 'rm -rf "$STATE_TMP"' EXIT INT TERM
"$JAVA_HOME/bin/java" -ea -cp "$BUILD/classes" com.froglike6.continuitybridge.StateEncryptionSuite "$STATE_TMP"
rm -rf "$STATE_TMP"
trap - EXIT INT TERM
