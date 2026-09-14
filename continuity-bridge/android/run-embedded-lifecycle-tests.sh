#!/bin/sh
set -eu
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_java
JAVA_RUNTIME="$JAVA_HOME"
TEST_BUILD=$(mktemp -d "${TMPDIR:-/tmp}/continuity-helper-lifecycle.XXXXXX")
trap 'rm -rf "$TEST_BUILD"' EXIT INT TERM
find "$ANDROID_DIR/lifecycle-test" -name '*.java' -print > "$TEST_BUILD/sources.txt"
for source in EmbeddedHelperManager EmbeddedHelperPreferences HelperBootReceiver; do
    printf '%s\n' "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/$source.java" >> "$TEST_BUILD/sources.txt"
done
"$JAVA_RUNTIME/bin/javac" -Xlint:all -Werror -encoding UTF-8 -d "$TEST_BUILD/classes" @"$TEST_BUILD/sources.txt"
"$JAVA_RUNTIME/bin/java" -ea -cp "$TEST_BUILD/classes" com.froglike6.continuitybridge.EmbeddedHelperLifecycleSuite
