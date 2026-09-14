#!/bin/sh
set -eu
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_java
JAVA_RUNTIME="$JAVA_HOME"
DISCOVERY_SOURCE="${1:-$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/LocalAdbDiscovery.java}"
TEST_BUILD=$(mktemp -d "${TMPDIR:-/tmp}/continuity-helper-discovery.XXXXXX")
trap 'rm -rf "$TEST_BUILD"' EXIT INT TERM
cp "$DISCOVERY_SOURCE" "$TEST_BUILD/LocalAdbDiscovery.java"
cd "$ANDROID_DIR"
find discovery-test -name '*.java' -print > "$TEST_BUILD/sources.txt"
printf '"%s"\n' "$TEST_BUILD/LocalAdbDiscovery.java" >> "$TEST_BUILD/sources.txt"
"$JAVA_RUNTIME/bin/javac" -Xlint:all -Werror -encoding UTF-8 -d "$TEST_BUILD/classes" @"$TEST_BUILD/sources.txt"
"$JAVA_RUNTIME/bin/java" -ea -cp "$TEST_BUILD/classes" com.froglike6.continuitybridge.LocalAdbDiscoverySuite
