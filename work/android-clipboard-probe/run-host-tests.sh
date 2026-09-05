#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
TEST_BUILD_DIR="$PROJECT_DIR/build/host-tests"
SOURCE_ROOT="$PROJECT_DIR/app/src/main/java"
CLASSIFIER="$PROJECT_DIR/app/src/main/java/com/froglike6/clipboardprobe/VerdictClassifier.java"
LIFECYCLE="$PROJECT_DIR/app/src/main/java/com/froglike6/clipboardprobe/TrialLifecycle.java"
HARNESS="$PROJECT_DIR/host-tests/VerdictClassifierAssertions.java"
LOG_MATCHER_HARNESS="$PROJECT_DIR/host-tests/ClipboardLogMatcherAssertions.java"

rm -rf "$TEST_BUILD_DIR"
mkdir -p "$TEST_BUILD_DIR"
"$JAVAC" -Xlint:all -Xlint:-options -source 8 -target 8 -sourcepath "$SOURCE_ROOT" \
    -d "$TEST_BUILD_DIR" \
    "$CLASSIFIER" "$LIFECYCLE" "$HARNESS" "$LOG_MATCHER_HARNESS"
"$JAVA" -ea -cp "$TEST_BUILD_DIR" com.froglike6.clipboardprobe.VerdictClassifierAssertions
"$JAVA" -ea -cp "$TEST_BUILD_DIR" com.froglike6.clipboardprobe.ClipboardLogMatcherAssertions
