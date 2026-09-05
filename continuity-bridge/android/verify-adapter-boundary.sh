#!/bin/sh
set -eu
MODE=${1:?mode required}
SOURCE=${2:?source root required}
CLASSES=${3:?classes root required}
APK=${4:?apk required}
JAVAP="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javap"
AAPT2=/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/aapt2
APK_ANALYZER=/opt/homebrew/bin/apkanalyzer

fail() { echo "ADAPTER_BOUNDARY_FAIL=$1" >&2; exit 1; }
contains_tree() { grep -R -I -F -q -- "$1" "$2" 2>/dev/null; }
defined_classes() { "$APK_ANALYZER" dex packages --defined-only "$1" | awk '$1 == "C" && $2 == "d" { print $6 }'; }

test -d "$SOURCE" || fail source_missing
test -d "$CLASSES" || fail classes_missing
test -s "$APK" || fail apk_missing

case "$MODE" in
production)
    APPLIER="$SOURCE/java/com/froglike6/continuitybridge/AndroidClipboardApplier.java"
    OVERLAY="$SOURCE/java/com/froglike6/continuitybridge/ClipboardOverlayActivity.java"
    test -s "$APPLIER" || fail applier_source_missing
    test -s "$OVERLAY" || fail overlay_source_missing
    contains_tree 'package com.froglike6.continuityfixture' "$SOURCE" && fail fixture_package_in_production_source
    find "$SOURCE" -type f | grep -Eqi '(^|/)[^/]*fixture[^/]*$' && fail fixture_named_source_or_resource
    find "$CLASSES" -type f | grep -Fq '/com/froglike6/continuityfixture/' && fail fixture_class_in_production_classes

    grep -Fq 'EVENT_ID_EXTRA = "com.froglike6.continuitybridge.EVENT_ID"' "$APPLIER" || fail event_id_key_source_missing
    grep -Fq 'extras.putString(EVENT_ID_EXTRA, eventId)' "$APPLIER" || fail event_id_extra_value_source_missing
    grep -Fq 'clip.getDescription().setExtras(extras)' "$APPLIER" || fail clip_description_extras_source_missing
    APPLIER_DUMP=$("$JAVAP" -classpath "$CLASSES" -c -p -verbose com.froglike6.continuitybridge.AndroidClipboardApplier)
    printf '%s\n' "$APPLIER_DUMP" | grep -Fq 'com.froglike6.continuitybridge.EVENT_ID' || fail event_id_key_bytecode_missing
    printf '%s\n' "$APPLIER_DUMP" | grep -Fq 'PersistableBundle.putString' || fail event_id_put_bytecode_missing
    printf '%s\n' "$APPLIER_DUMP" | grep -Fq 'ClipDescription.setExtras' || fail clip_description_extras_bytecode_missing

    grep -Fq 'WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY' "$OVERLAY" || fail overlay_type_source_missing
    grep -Fq 'setFocusableInTouchMode(true)' "$OVERLAY" || fail overlay_focusable_source_missing
    grep -Fq 'overlay.requestFocus()' "$OVERLAY" || fail overlay_request_focus_source_missing
    grep -Eq 'FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM|setFocusable(InTouchMode)?\(false\)|clearFocus\(' "$OVERLAY" && fail overlay_focus_breaking_source
    OVERLAY_DUMP=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuitybridge.ClipboardOverlayActivity)
    printf '%s\n' "$OVERLAY_DUMP" | grep -Fq 'View.setFocusableInTouchMode' || fail overlay_focusable_bytecode_missing
    printf '%s\n' "$OVERLAY_DUMP" | grep -Fq 'View.requestFocus' || fail overlay_request_focus_bytecode_missing

    defined_classes "$APK" | grep -Fq 'com.froglike6.continuityfixture' && fail fixture_class_in_production_dex
    defined_classes "$APK" | grep -Ev '^com\.froglike6\.continuitybridge(\.|$)' | grep -q . && fail foreign_class_in_production_dex
    /usr/bin/unzip -l "$APK" | grep -Eqi 'fixture' && fail fixture_resource_in_production_apk
    echo 'ADAPTER_BOUNDARY_OK mode=production marker=source+bytecode focus=source+bytecode fixture_separation=source+classes+dex+apk'
    ;;
fixture)
    MANIFEST="$SOURCE/main/AndroidManifest.xml"
    ACTIVITY="$SOURCE/main/java/com/froglike6/continuityfixture/FixtureActivity.java"
    test -s "$MANIFEST" || fail fixture_manifest_missing
    test -s "$ACTIVITY" || fail fixture_activity_missing
    grep -REq '^[[:space:]]*(package|import)[[:space:]]+com\.froglike6\.continuitybridge' "$SOURCE" && fail production_package_in_fixture_source
    EXPECTED_FIXTURE_TREE=$(printf '%s\n' \
        main \
        main/AndroidManifest.xml \
        main/java \
        main/java/com \
        main/java/com/froglike6 \
        main/java/com/froglike6/continuityfixture \
        main/java/com/froglike6/continuityfixture/FixtureActivity.java)
    ACTUAL_FIXTURE_TREE=$(find "$SOURCE/main" -print | sed "s#^$SOURCE/##" | LC_ALL=C sort)
    test "$ACTUAL_FIXTURE_TREE" = "$EXPECTED_FIXTURE_TREE" || fail fixture_source_inventory_unowned
    grep -Fq 'package="com.froglike6.continuityfixture"' "$MANIFEST" || fail fixture_manifest_package_source
    grep -Fq 'package com.froglike6.continuityfixture;' "$ACTIVITY" || fail fixture_activity_package_source
    find "$CLASSES" -type f | grep -Fq '/com/froglike6/continuitybridge/' && fail production_class_in_fixture_classes
    defined_classes "$APK" | grep -Fq 'com.froglike6.continuitybridge' && fail production_class_in_fixture_dex
    defined_classes "$APK" | grep -Ev '^com\.froglike6\.continuityfixture(\.|$)' | grep -q . && fail foreign_class_in_fixture_dex
    BADGING=$($AAPT2 dump badging "$APK")
    printf '%s\n' "$BADGING" | grep -Fq "package: name='com.froglike6.continuityfixture'" || fail fixture_package_missing
    printf '%s\n' "$BADGING" | grep -Fq "launchable-activity: name='com.froglike6.continuityfixture.FixtureActivity'" || fail fixture_launcher_missing
    echo 'ADAPTER_BOUNDARY_OK mode=fixture separation=exact-source-inventory+classes+dex package+launcher=verified'
    ;;
*) fail invalid_mode ;;
esac
