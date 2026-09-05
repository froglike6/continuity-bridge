#!/bin/sh
set -eu

OUTPUT_DIR=${1:-continuity-bridge/runtime/auth}
mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"
OPENSSL_BIN=${OPENSSL_BIN:-/opt/homebrew/bin/openssl}
if [ ! -x "$OPENSSL_BIN" ]; then OPENSSL_BIN=$(command -v openssl); fi
ANDROID_TOKEN=$($OPENSSL_BIN rand -hex 32)
MACOS_TOKEN=$($OPENSSL_BIN rand -hex 32)
REVOKED_TOKEN=$($OPENSSL_BIN rand -hex 32)
umask 077
printf '{"credentials":[{"token":"%s","role":"android","deviceId":"device-android-local","revoked":false},{"token":"%s","role":"macos","deviceId":"device-macos-local","revoked":false},{"token":"%s","role":"android","deviceId":"device-revoked-local","revoked":true}]}\n' \
  "$ANDROID_TOKEN" "$MACOS_TOKEN" "$REVOKED_TOKEN" > "$OUTPUT_DIR/auth.json"
chmod 600 "$OUTPUT_DIR/auth.json"
printf 'AUTH_READY credentials=3\n'
