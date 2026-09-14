#!/bin/sh
set -eu
fail_public_ca() {
    echo "ADAPTER_NEGATIVE_PREREQ_FAIL=public_ca_$1" >&2
    exit 1
}

APPROVED_PUBLIC_CA_SHA256=c6cfdabcf2ca0774883c80e23277360fb44e7430ddec3f99bb1925e9975ca86b
PUBLIC_CA_MIN_BYTES=1024
PUBLIC_CA_MAX_BYTES=4096

ANDROID_DIR=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
ROOT=$(CDPATH= cd -P -- "$ANDROID_DIR/../.." && pwd -P)
TLS_DIR="$ROOT/continuity-bridge/runtime/tls"
CA_SOURCE="$TLS_DIR/ca.pem"
test -d "$TLS_DIR" || fail_public_ca directory_missing
TLS_DIR_PHYSICAL=$(CDPATH= cd -P -- "$TLS_DIR" && pwd -P) || fail_public_ca directory_unreadable
test "$TLS_DIR_PHYSICAL" = "$TLS_DIR" || fail_public_ca directory_symlink_escape
test -e "$CA_SOURCE" || fail_public_ca missing
test ! -L "$CA_SOURCE" || fail_public_ca symlink
test -f "$CA_SOURCE" || fail_public_ca not_regular
CA_BYTES=$(wc -c < "$CA_SOURCE" | tr -d ' ')
test "$CA_BYTES" -ge "$PUBLIC_CA_MIN_BYTES" || fail_public_ca size_too_small
test "$CA_BYTES" -le "$PUBLIC_CA_MAX_BYTES" || fail_public_ca size_too_large
command -v openssl >/dev/null 2>&1 || fail_public_ca openssl_missing
openssl x509 -in "$CA_SOURCE" -noout >/dev/null 2>&1 || fail_public_ca invalid_pem
openssl x509 -in "$CA_SOURCE" -noout -ext basicConstraints 2>/dev/null | grep -Fq 'CA:TRUE' || fail_public_ca ca_constraints
openssl verify -CAfile "$CA_SOURCE" "$CA_SOURCE" >/dev/null 2>&1 || fail_public_ca x509_validity
CA_SHA256=$(shasum -a 256 "$CA_SOURCE" | awk '{print $1}')
test "$CA_SHA256" = "$APPROVED_PUBLIC_CA_SHA256" || fail_public_ca sha256
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/continuity-task4-negative.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT INT TERM
mkdir -p "$TEMP/continuity-bridge"; cp -R "$ANDROID_DIR" "$TEMP/continuity-bridge/android"
mkdir -p "$TEMP/continuity-bridge/runtime/tls"
cp "$CA_SOURCE" "$TEMP/continuity-bridge/runtime/tls/ca.pem"
MUTANT="$TEMP/continuity-bridge/android"

perl -0pi -e 's/clipboardCapture\.start\(\);/clipboardCapture.stop();/' "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/BridgeService.java"
set +e; "$MUTANT/build.sh" > "$TEMP/clipboard-bypass.txt" 2>&1; CLIP_EXIT=$?; set -e
test "$CLIP_EXIT" -ne 0; grep -Fq 'SERVICE_WIRING_MISSING=ClipboardCaptureController.start' "$TEMP/clipboard-bypass.txt"
echo "NEGATIVE_CLIPBOARD_WIRING_EXIT=$CLIP_EXIT marker=SERVICE_WIRING_MISSING"

cp "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/BridgeService.java" "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/BridgeService.java"
perl -0pi -e 's/BridgeRepository\.get\(this\)\.outbox\(\)\.enqueueNotification\(fields, System\.currentTimeMillis\(\)\)/disabled(fields)/; s/(final class NotificationMirrorService[^\{]*\{)/$1\n    private boolean disabled(NotificationFields fields) throws IOException { return false; }/' "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/NotificationMirrorService.java"
set +e; "$MUTANT/build.sh" > "$TEMP/notification-bypass.txt" 2>&1; NOTE_EXIT=$?; set -e
test "$NOTE_EXIT" -ne 0
if ! grep -Fq 'MIRROR_WIRING_MISSING=enqueueNotification' "$TEMP/notification-bypass.txt"; then cat "$TEMP/notification-bypass.txt" >&2; exit 1; fi
echo "NEGATIVE_NOTIFICATION_WIRING_EXIT=$NOTE_EXIT marker=MIRROR_WIRING_MISSING"

cp "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/NotificationMirrorService.java" "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/NotificationMirrorService.java"
perl -0pi -e 's#<service android:name="\.NotificationMirrorService".*?</service>##s' "$MUTANT/app/src/main/AndroidManifest.xml"
set +e; "$MUTANT/build.sh" > "$TEMP/manifest-missing.txt" 2>&1; MANIFEST_EXIT=$?; set -e
test "$MANIFEST_EXIT" -ne 0
echo "NEGATIVE_MANIFEST_CAPABILITY_EXIT=$MANIFEST_EXIT"

cp "$ANDROID_DIR/app/src/main/AndroidManifest.xml" "$MUTANT/app/src/main/AndroidManifest.xml"
perl -0pi -e 's#android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"##' "$MUTANT/app/src/main/AndroidManifest.xml"
set +e; "$MUTANT/verify-manifest-source.sh" "$MUTANT/app/src/main/AndroidManifest.xml" > "$TEMP/shizuku-provider-unprotected.txt" 2>&1; PROVIDER_EXIT=$?; set -e
test "$PROVIDER_EXIT" -ne 0; grep -Fq 'MANIFEST_PROVIDER_ATTRIBUTES_MISMATCH=.ClipboardHelperProvider' "$TEMP/shizuku-provider-unprotected.txt"
echo "NEGATIVE_SHIZUKU_PROVIDER_EXIT=$PROVIDER_EXIT marker=MANIFEST_PROVIDER_ATTRIBUTES_MISMATCH"

cp "$ANDROID_DIR/app/src/main/AndroidManifest.xml" "$MUTANT/app/src/main/AndroidManifest.xml"
perl -0pi -e 's#(<application )#<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n    $1#' "$MUTANT/app/src/main/AndroidManifest.xml"
set +e; "$MUTANT/verify-manifest-source.sh" "$MUTANT/app/src/main/AndroidManifest.xml" > "$TEMP/legacy-overlay-permission.txt" 2>&1; PERMISSION_EXIT=$?; set -e
test "$PERMISSION_EXIT" -ne 0; grep -Fq 'MANIFEST_PERMISSION_UNEXPECTED=android.permission.SYSTEM_ALERT_WINDOW' "$TEMP/legacy-overlay-permission.txt"
echo "NEGATIVE_OVERLAY_PERMISSION_EXIT=$PERMISSION_EXIT marker=MANIFEST_PERMISSION_UNEXPECTED"

cp "$ANDROID_DIR/app/src/main/AndroidManifest.xml" "$MUTANT/app/src/main/AndroidManifest.xml"
perl -0pi -e 's#<action android:name="android.intent.action.MAIN" />##' "$MUTANT/fixture/src/main/AndroidManifest.xml"
set +e; "$MUTANT/build-fixture.sh" > "$TEMP/fixture-missing.txt" 2>&1; FIXTURE_EXIT=$?; set -e
test "$FIXTURE_EXIT" -ne 0; grep -Fq 'ADAPTER_BOUNDARY_FAIL=fixture_launcher_missing' "$TEMP/fixture-missing.txt"
echo "NEGATIVE_FIXTURE_CAPABILITY_EXIT=$FIXTURE_EXIT marker=fixture_launcher_missing"

cp "$ANDROID_DIR/fixture/src/main/AndroidManifest.xml" "$MUTANT/fixture/src/main/AndroidManifest.xml"
perl -0pi -e 's/clip\.getDescription\(\)\.setExtras\(extras\);//' "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/AndroidClipboardApplier.java"
set +e; "$MUTANT/build.sh" > "$TEMP/event-extras-missing.txt" 2>&1; EXTRAS_EXIT=$?; set -e
test "$EXTRAS_EXIT" -ne 0; grep -Fq 'ADAPTER_BOUNDARY_FAIL=clip_description_extras_source_missing' "$TEMP/event-extras-missing.txt"
! grep -Fq 'ANDROID_BUILD_OK' "$TEMP/event-extras-missing.txt"
echo "NEGATIVE_EVENT_EXTRAS_EXIT=$EXTRAS_EXIT marker=clip_description_extras_source_missing success_markers=0"

cp "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/AndroidClipboardApplier.java" "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/AndroidClipboardApplier.java"
perl -0pi -e 's/(final class ClipboardCaptureController[^\{]*\{)/$1\n    private static void forbiddenFocus(android.content.Context context) { context.startActivity(new android.content.Intent()); }/' "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/ClipboardCaptureController.java"
set +e; "$MUTANT/build.sh" > "$TEMP/clipboard-focus-fallback.txt" 2>&1; FOCUS_EXIT=$?; set -e
test "$FOCUS_EXIT" -ne 0; grep -Fq 'CLIPBOARD_FORBIDDEN_BYTECODE=ClipboardCaptureController' "$TEMP/clipboard-focus-fallback.txt"
! grep -Fq 'ANDROID_BUILD_OK' "$TEMP/clipboard-focus-fallback.txt"
echo "NEGATIVE_CLIPBOARD_FOCUS_EXIT=$FOCUS_EXIT marker=CLIPBOARD_FORBIDDEN_BYTECODE success_markers=0"

cp "$ANDROID_DIR/app/src/main/java/com/froglike6/continuitybridge/ClipboardCaptureController.java" "$MUTANT/app/src/main/java/com/froglike6/continuitybridge/ClipboardCaptureController.java"
mkdir -p "$MUTANT/app/src/main/java/com/froglike6/continuityfixture"
cp "$ANDROID_DIR/fixture/src/main/java/com/froglike6/continuityfixture/FixtureActivity.java" "$MUTANT/app/src/main/java/com/froglike6/continuityfixture/FixtureActivity.java"
set +e; "$MUTANT/build.sh" > "$TEMP/fixture-in-production.txt" 2>&1; PROD_LEAK_EXIT=$?; set -e
test "$PROD_LEAK_EXIT" -ne 0; grep -Fq 'ADAPTER_BOUNDARY_FAIL=fixture_package_in_production_source' "$TEMP/fixture-in-production.txt"
! grep -Fq 'ANDROID_BUILD_OK' "$TEMP/fixture-in-production.txt"
echo "NEGATIVE_FIXTURE_IN_PRODUCTION_EXIT=$PROD_LEAK_EXIT marker=fixture_package_in_production_source success_markers=0"

mkdir -p "$MUTANT/fixture/src/main/java/com/froglike6/continuitybridge"
printf '%s\n' 'package com.froglike6.continuitybridge;' 'public final class LeakedProductionClass { }' > "$MUTANT/fixture/src/main/java/com/froglike6/continuitybridge/LeakedProductionClass.java"
set +e; "$MUTANT/build-fixture.sh" > "$TEMP/production-in-fixture.txt" 2>&1; FIX_LEAK_EXIT=$?; set -e
test "$FIX_LEAK_EXIT" -ne 0; grep -Fq 'ADAPTER_BOUNDARY_FAIL=production_package_in_fixture_source' "$TEMP/production-in-fixture.txt"
! grep -Fq 'FIXTURE_BUILD_OK' "$TEMP/production-in-fixture.txt"
echo "NEGATIVE_PRODUCTION_IN_FIXTURE_EXIT=$FIX_LEAK_EXIT marker=production_package_in_fixture_source success_markers=0"

run_fixture_resource_mutation() {
    NAME=$1
    RELATIVE=$2
    RESOURCE_MUTANT="$TEMP/resource-$NAME/continuity-bridge/android"
    mkdir -p "$TEMP/resource-$NAME/continuity-bridge"
    cp -R "$ANDROID_DIR" "$RESOURCE_MUTANT"
    mkdir -p "$(dirname "$RESOURCE_MUTANT/fixture/src/main/$RELATIVE")"
    case "$RELATIVE" in
        *.xml) printf '%s\n' '<fixture-boundary-probe />' > "$RESOURCE_MUTANT/fixture/src/main/$RELATIVE" ;;
        *) printf '%s\n' 'fixture boundary probe' > "$RESOURCE_MUTANT/fixture/src/main/$RELATIVE" ;;
    esac
    set +e
    "$RESOURCE_MUTANT/build-fixture.sh" > "$TEMP/resource-$NAME.txt" 2>&1
    RESOURCE_EXIT=$?
    set -e
    test "$RESOURCE_EXIT" -ne 0
    grep -Fq 'ADAPTER_BOUNDARY_FAIL=fixture_source_inventory_unowned' "$TEMP/resource-$NAME.txt"
    ! grep -Fq 'FIXTURE_BUILD_OK' "$TEMP/resource-$NAME.txt"
    echo "NEGATIVE_FIXTURE_RESOURCE_EXIT=$RESOURCE_EXIT name=$NAME path=$RELATIVE marker=fixture_source_inventory_unowned success_markers=0"
}

run_fixture_resource_mutation raw_production_marker res/raw/continuitybridge_probe.txt
run_fixture_resource_mutation asset_production_marker assets/continuitybridge_probe.txt
run_fixture_resource_mutation xml_production_marker res/xml/continuitybridge_probe.xml
run_fixture_resource_mutation unowned_benign_name res/raw/unowned_probe.txt
echo 'ADAPTER_NEGATIVE_OK mutations=clipboard_wiring,notification_wiring,production_manifest,shizuku_provider_permission,overlay_permission,fixture_launcher,event_extras,clipboard_focus_fallback,fixture_in_production,production_in_fixture,fixture_raw_resource,fixture_asset,fixture_xml,fixture_unowned_name'
