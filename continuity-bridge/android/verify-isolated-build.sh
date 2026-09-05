#!/bin/sh
set -eu
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$ANDROID_DIR/../.." && pwd)
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/continuity-task4-isolated.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT INT TERM
mkdir -p "$TEMP/continuity-bridge/runtime/tls"
cp -R "$ANDROID_DIR" "$TEMP/continuity-bridge/android"
cp "$ROOT/continuity-bridge/runtime/tls/ca.pem" "$TEMP/continuity-bridge/runtime/tls/ca.pem"
ISOLATED="$TEMP/continuity-bridge/android"
"$ISOLATED/build.sh"
"$ISOLATED/build-fixture.sh"
PROD="$TEMP/outputs/continuity-bridge-android-debug.apk"
FIXTURE="$TEMP/outputs/continuity-fixture-debug.apk"
test -s "$PROD"; test -s "$FIXTURE"
shasum -a 256 "$PROD" "$FIXTURE"
printf 'product_logical_sha256='
for entry in AndroidManifest.xml resources.arsc classes.dex; do unzip -p "$PROD" "$entry"; done | shasum -a 256 | awk '{print $1}'
printf 'fixture_logical_sha256='
for entry in AndroidManifest.xml resources.arsc classes.dex; do unzip -p "$FIXTURE" "$entry"; done | shasum -a 256 | awk '{print $1}'
echo 'ISOLATED_BUILD_OK canonical_outputs_untouched=1 teardown_registered=1'
