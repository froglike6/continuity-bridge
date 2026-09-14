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
    CAPTURE="$SOURCE/java/com/froglike6/continuitybridge/ClipboardCaptureController.java"
    test -s "$APPLIER" || fail applier_source_missing
    test -s "$CAPTURE" || fail capture_source_missing
    test ! -e "$SOURCE/java/com/froglike6/continuitybridge/ClipboardOverlayActivity.java" || fail legacy_overlay_source_present
    for adapter in "$APPLIER" "$CAPTURE" "$SOURCE"/java/com/froglike6/continuitybridge/ShizukuClipboard*.java; do
        test -s "$adapter" || fail shizuku_source_missing
        grep -Eq 'ClipboardOverlayActivity|WindowManager|startActivity|ProcessBuilder|READ_LOGS|SYSTEM_ALERT_WINDOW|"logcat"' "$adapter" && fail clipboard_focus_or_process_source
    done
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

    grep -Fq 'ShizukuClipboardClient' "$CAPTURE" || fail shizuku_capture_source_missing
    grep -Fq 'ShizukuClipboardClient' "$APPLIER" || fail shizuku_applier_source_missing
    test ! -e "$CLASSES/com/froglike6/continuitybridge/ClipboardOverlayActivity.class" || fail legacy_overlay_class_present

    DEX_CLASSES=$(defined_classes "$APK")
    printf '%s\n' "$DEX_CLASSES" | grep -Fq 'com.froglike6.continuityfixture' && fail fixture_class_in_production_dex
    printf '%s\n' "$DEX_CLASSES" | grep -Fq 'ClipboardOverlayActivity' && fail legacy_overlay_dex_present
    printf '%s\n' "$DEX_CLASSES" | grep -Ev '^(com\.froglike6\.continuitybridge|io\.github\.muntashirakon\.(adb|crypto\.spake2)|org\.(bouncycastle|conscrypt))(\.|$)' | grep -q . && fail foreign_class_in_production_dex
    printf '%s\n' "$DEX_CLASSES" | grep -Fxq 'com.froglike6.continuitybridge.ClipboardHelperProvider' || fail helper_provider_dex_missing
    printf '%s\n' "$DEX_CLASSES" | grep -Fxq 'com.froglike6.continuitybridge.ClipboardHelperMain' || fail helper_main_dex_missing
    printf '%s\n' "$DEX_CLASSES" | grep -Eq '^(rikka\.shizuku|rikka\.sui|moe\.shizuku)(\.|$)' && fail external_manager_sdk_present
    /usr/bin/unzip -l "$APK" | grep -Eqi 'fixture' && fail fixture_resource_in_production_apk
    echo 'ADAPTER_BOUNDARY_OK mode=production marker=source+bytecode capture=embedded-helper external_manager=absent legacy_overlay=absent fixture_separation=source+classes+dex+apk'
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
        main/java/com/froglike6/continuityfixture/ClipboardImageFixtureActivity.java \
        main/java/com/froglike6/continuityfixture/ClipboardImageFixtureProvider.java \
        main/java/com/froglike6/continuityfixture/FixtureActivity.java \
        main/java/com/froglike6/continuityfixture/NotificationFixtureReceiver.java \
        main/res \
        main/res/drawable \
        main/res/drawable/ic_fixture_chat.xml \
        main/res/drawable/ic_fixture_download.xml)
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
