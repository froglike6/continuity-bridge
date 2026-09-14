#!/bin/sh
set -eu
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$ANDROID_DIR/../.." && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_java
TASK_TEMP=$(mktemp -d /tmp/continuity-task3-bounds.XXXXXX)
cleanup() { find "$TASK_TEMP" -type f -delete; find "$TASK_TEMP" -depth -type d -empty -delete; }
trap cleanup EXIT INT TERM
run_mutation() {
    name=$1
    expected=$2
    find "$TASK_TEMP" -type f -delete
    find "$TASK_TEMP" -depth -type d -empty -delete
    mkdir -p "$TASK_TEMP/core" "$TASK_TEMP/test" "$TASK_TEMP/classes"
    cp "$ANDROID_DIR"/app/src/main/java/com/froglike6/continuitybridge/core/*.java "$TASK_TEMP/core/"
    cp "$ANDROID_DIR"/app/src/main/java/com/froglike6/continuitybridge/ConnectionStatus.java "$TASK_TEMP/test/"
    cp "$ANDROID_DIR"/app/src/main/java/com/froglike6/continuitybridge/UiStatePolicy.java "$TASK_TEMP/test/"
    cp "$ANDROID_DIR"/host-test/com/froglike6/continuitybridge/*.java "$TASK_TEMP/test/"
    if test "$name" = fingerprint; then
        /usr/bin/perl -pi -e 's/prints\.size\(\) > appliedLimit/prints.size() < 0/' "$TASK_TEMP/core/BridgeState.java"
    else
        /usr/bin/perl -pi -e 's/candidate\.expiresAtMs\(\) <= nowMs/candidate.expiresAtMs() < 0/' "$TASK_TEMP/core/BridgeState.java"
    fi
    find "$TASK_TEMP/core" "$TASK_TEMP/test" -name '*.java' -print | LC_ALL=C sort > "$TASK_TEMP/sources.txt"
    "$JAVA_HOME/bin/javac" -Xlint:all -Werror -encoding UTF-8 -d "$TASK_TEMP/classes" @"$TASK_TEMP/sources.txt"
    set +e
    output=$("$JAVA_HOME/bin/java" -ea -cp "$TASK_TEMP/classes" com.froglike6.continuitybridge.HostSuite \
        "$ROOT/continuity-bridge/protocol/fixtures" "$ROOT/continuity-bridge/runtime/tls" 2>&1)
    status=$?
    set -e
    test "$status" -ne 0
    printf '%s\n' "$output" | grep -Fq "$expected"
    if printf '%s\n' "$output" | grep -Fq 'HOST_SUITE_OK'; then exit 1; fi
    printf 'BOUNDED_NEGATIVE_OK mutation=%s exit=%s assertion=%s\n' "$name" "$status" "$expected"
}
run_mutation fingerprint fingerprint_bound
run_mutation expiry notification_expired_first
