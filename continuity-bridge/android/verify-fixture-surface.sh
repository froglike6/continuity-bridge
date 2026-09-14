#!/bin/sh
set -eu
STATIC_ONLY=0
if test "${1:-}" = --static; then
    STATIC_ONLY=1; SOURCE=$2; CLASSES=$3
else
    APK=${1:-continuity-bridge/android/build/fixture/continuity-fixture-debug.apk}
    SOURCE=${2:-continuity-bridge/android/fixture/src/main/java/com/froglike6/continuityfixture/FixtureActivity.java}
    CLASSES=${3:-continuity-bridge/android/build/fixture/classes}
fi
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_java
if test "$STATIC_ONLY" = 0; then continuity_android_tools; fi
fail() { echo "FIXTURE_SURFACE_FAIL=$1" >&2; exit 1; }

test -s "$SOURCE" || fail activity_source_missing
test -d "$CLASSES" || fail classes_missing
if test "$STATIC_ONLY" = 0; then
    test -s "$APK" || fail apk_missing
    BADGING=$("$AAPT2" dump badging "$APK")
    printf '%s\n' "$BADGING" | grep -Fq "package: name='com.froglike6.continuityfixture'" || fail package_missing
    printf '%s\n' "$BADGING" | grep -Fq "launchable-activity: name='com.froglike6.continuityfixture.FixtureActivity'" || fail launcher_missing
fi

grep -Fq 'AVD 전경 파이프라인 테스트' "$SOURCE" || fail foreground_control_missing
grep -Fq 'new Intent()' "$SOURCE" || fail explicit_intent_missing
grep -Fq 'setClassName(PRODUCT_PACKAGE, PRODUCT_MAIN_ACTIVITY)' "$SOURCE" || fail product_component_missing
grep -Fq 'startActivity(product)' "$SOURCE" || fail product_launch_missing
grep -Fq 'private static final Handler FOREGROUND_PIPELINE_HANDLER' "$SOURCE" || fail application_handler_missing
grep -Fq 'private static final class PipelineClipboardWrite implements Runnable' "$SOURCE" || fail application_write_runnable_missing
grep -Fq 'context.getApplicationContext()' "$SOURCE" || fail application_context_missing
grep -Fq 'new PipelineClipboardWrite(getApplicationContext(), text)' "$SOURCE" || fail application_write_schedule_missing
grep -Fq 'FOREGROUND_PIPELINE_HANDLER.postDelayed' "$SOURCE" || fail delayed_write_missing
grep -Fq 'foregroundPipelineHandler' "$SOURCE" && fail activity_handler_retained

START_LINE=$(grep -n -F 'startActivity(product)' "$SOURCE" | cut -d: -f1)
DELAY_LINE=$(grep -n -F 'FOREGROUND_PIPELINE_HANDLER.postDelayed' "$SOURCE" | cut -d: -f1)
test "$START_LINE" -lt "$DELAY_LINE" || fail source_launch_after_delay

PIPELINE_DUMP=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuityfixture.FixtureActivity)
START_OFFSET=$(printf '%s\n' "$PIPELINE_DUMP" | nl -ba | grep -F 'Method startActivity' | awk '{print $1}' | head -1)
DELAY_OFFSET=$(printf '%s\n' "$PIPELINE_DUMP" | nl -ba | grep -F 'Handler.postDelayed' | awk '{print $1}' | head -1)
test -n "$START_OFFSET" || fail product_launch_bytecode_missing
test -n "$DELAY_OFFSET" || fail delayed_write_bytecode_missing
test "$START_OFFSET" -lt "$DELAY_OFFSET" || fail bytecode_launch_after_delay
printf '%s\n' "$PIPELINE_DUMP" | grep -Fq 'com.froglike6.continuitybridge.MainActivity' || fail product_component_bytecode_missing
printf '%s\n' "$PIPELINE_DUMP" | grep -Fq 'FOREGROUND_PIPELINE_HANDLER' || fail application_handler_bytecode_missing
PIPELINE_WRITE_DUMP=$("$JAVAP" -classpath "$CLASSES" -c -p 'com.froglike6.continuityfixture.FixtureActivity$PipelineClipboardWrite')
printf '%s\n' "$PIPELINE_WRITE_DUMP" | grep -Fq 'android.content.Context applicationContext;' || fail application_context_field_missing
printf '%s\n' "$PIPELINE_WRITE_DUMP" | grep -Fq 'android/content/Context.getApplicationContext' || fail application_context_bytecode_missing
printf '%s\n' "$PIPELINE_WRITE_DUMP" | grep -Fq 'android/content/ClipboardManager.setPrimaryClip' || fail delayed_clipboard_write_missing
printf '%s\n' "$PIPELINE_WRITE_DUMP" | grep -Eq 'FixtureActivity( this\$0|;)' && fail activity_reference_retained

echo "FIXTURE_SURFACE_OK mode=$(test \"$STATIC_ONLY\" = 1 && echo static || echo apk) foreground_pipeline=application_lifetime_source+bytecode"
