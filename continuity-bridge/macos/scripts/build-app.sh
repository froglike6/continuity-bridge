#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MACOS_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
WORKSPACE_DIR=$(CDPATH= cd -- "$MACOS_DIR/../.." && pwd)
APP_DIR="$MACOS_DIR/dist/ContinuityBridge.app"
CONTENTS_DIR="$APP_DIR/Contents"
OUTPUT_DIR="$WORKSPACE_DIR/outputs"
OUTPUT_ZIP="$OUTPUT_DIR/ContinuityBridge.app.zip"
OUTPUT_RECEIPT="$OUTPUT_ZIP.sha256"
CA_SOURCE="${CONTINUITY_CA_PEM:-}"
PIN_SOURCE="${CONTINUITY_LEAF_PIN_FILE:-}"
TRUST_MODE=system
RESOURCE_COUNT=1
BOOT_SMOKE=0

if [ "${1:-}" = "--boot-smoke" ]; then
    BOOT_SMOKE=1
elif [ "$#" -ne 0 ]; then
    echo "usage: $0 [--boot-smoke]" >&2
    exit 64
fi

case "$APP_DIR" in
    "$MACOS_DIR"/dist/ContinuityBridge.app) ;;
    *) echo "unsafe app output path" >&2; exit 65 ;;
esac

if [ -n "$CA_SOURCE" ] || [ -n "$PIN_SOURCE" ]; then
    if [ -z "$CA_SOURCE" ] || [ -z "$PIN_SOURCE" ]; then
        echo 'Set both CONTINUITY_CA_PEM and CONTINUITY_LEAF_PIN_FILE for local TLS.' >&2
        exit 68
    fi
    for TRUST_INPUT in "$CA_SOURCE" "$PIN_SOURCE"; do
        case "$TRUST_INPUT" in
            /*) ;;
            *) echo 'Trust input paths must be absolute.' >&2; exit 68 ;;
        esac
        TRUST_PARENT=$(dirname "$TRUST_INPUT")
        TRUST_PHYSICAL=$(CDPATH= cd -P -- "$TRUST_PARENT" && pwd -P) || exit 68
        if [ "$TRUST_PHYSICAL" != "$TRUST_PARENT" ] || [ ! -f "$TRUST_INPUT" ] || [ -L "$TRUST_INPUT" ]; then
            echo 'Trust inputs must be regular files without symlink parents.' >&2
            exit 68
        fi
    done
    CA_BYTES=$(wc -c < "$CA_SOURCE" | tr -d ' ')
    if [ "$CA_BYTES" -gt 16384 ] || \
        ! awk '
            /^-----BEGIN CERTIFICATE-----$/ { if (state != 0) exit 1; state=1; next }
            /^-----END CERTIFICATE-----$/ { if (state != 1) exit 1; state=2; next }
            /^[A-Za-z0-9+\/=]+$/ { if (state != 1) exit 1; next }
            /^$/ { next }
            { exit 1 }
            END { if (state != 2) exit 1 }
        ' "$CA_SOURCE" || \
        ! openssl x509 -in "$CA_SOURCE" -noout >/dev/null 2>&1 || \
        ! openssl x509 -in "$CA_SOURCE" -noout -text 2>/dev/null | grep -Fq 'CA:TRUE' || \
        ! openssl verify -CAfile "$CA_SOURCE" "$CA_SOURCE" >/dev/null 2>&1; then
        echo 'Invalid local CA: require one currently valid public PEM CA certificate.' >&2
        exit 68
    fi
    PIN_BYTES=$(wc -c < "$PIN_SOURCE" | tr -d ' ')
    if { [ "$PIN_BYTES" -ne 64 ] && [ "$PIN_BYTES" -ne 65 ]; } || \
        ! LC_ALL=C grep -Eq '^[0-9a-f]{64}$' "$PIN_SOURCE"; then
        echo 'Invalid local pin: require 64 lowercase SHA-256 hex digits, with an optional final newline.' >&2
        exit 68
    fi
    TRUST_MODE=local
    RESOURCE_COUNT=3
fi

rg -q 'let applier = RemoteEventApplier' "$MACOS_DIR/Sources/ContinuityMenuBar/BridgeHostModel.swift"
rg -q 'apply: \{ event in try await applier\.apply\(event\) \}' \
    "$MACOS_DIR/Sources/ContinuityMenuBar/BridgeHostModel.swift"

swift build --package-path "$MACOS_DIR" -c release
BIN_DIR=$(swift build --package-path "$MACOS_DIR" -c release --show-bin-path)

rm -rf "$APP_DIR"
mkdir -p "$CONTENTS_DIR/MacOS" "$CONTENTS_DIR/Resources" "$OUTPUT_DIR"
cp "$BIN_DIR/ContinuityMenuBar" "$CONTENTS_DIR/MacOS/ContinuityBridge"
chmod 755 "$CONTENTS_DIR/MacOS/ContinuityBridge"
cp "$MACOS_DIR/Packaging/Info.plist" "$CONTENTS_DIR/Info.plist"
cp "$MACOS_DIR/Packaging/AppIcon.icns" "$CONTENTS_DIR/Resources/AppIcon.icns"
if [ "$TRUST_MODE" = local ]; then
    cp "$CA_SOURCE" "$CONTENTS_DIR/Resources/ca.pem"
    cp "$PIN_SOURCE" "$CONTENTS_DIR/Resources/server-cert.sha256"
fi

plutil -lint "$CONTENTS_DIR/Info.plist"
test "$(plutil -extract CFBundleIdentifier raw "$CONTENTS_DIR/Info.plist")" = \
    "com.froglike6.continuitybridge.macos"
test "$(plutil -extract LSUIElement raw "$CONTENTS_DIR/Info.plist")" = "true"
test "$(plutil -extract LSMinimumSystemVersion raw "$CONTENTS_DIR/Info.plist")" = "13.0"
test "$(plutil -extract CFBundleIconFile raw "$CONTENTS_DIR/Info.plist")" = "AppIcon.icns"
test -s "$CONTENTS_DIR/Resources/AppIcon.icns"
test "$(find "$CONTENTS_DIR/Resources" -type f | wc -l | tr -d ' ')" = "$RESOURCE_COUNT"
if rg -a -n -i 'BEGIN ([A-Z ]+ )?PRIVATE KEY|TASK6_PRIVATE_KEY_SENTINEL|TASK6_TOKEN_SENTINEL' "$APP_DIR"; then
    echo "forbidden private material in app bundle" >&2
    exit 66
fi

codesign --force --deep --sign - "$APP_DIR"
codesign --verify --deep --strict --verbose=2 "$APP_DIR"
find "$APP_DIR" -exec touch -h -t 202001010000 {} +
codesign --verify --deep --strict --verbose=2 "$APP_DIR"

rm -f "$OUTPUT_ZIP" "$OUTPUT_RECEIPT"
(CDPATH= cd -- "$MACOS_DIR/dist" && COPYFILE_DISABLE=1 /usr/bin/zip -X -qry "$OUTPUT_ZIP" ContinuityBridge.app)
unzip -tq "$OUTPUT_ZIP"
HASH=$(shasum -a 256 "$OUTPUT_ZIP" | awk '{print $1}')
printf '%s  %s\n' "$HASH" "ContinuityBridge.app.zip" > "$OUTPUT_RECEIPT"

if [ "$BOOT_SMOKE" -eq 1 ]; then
    SMOKE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/continuity-bridge-boot.XXXXXX")
    trap 'rm -rf "$SMOKE_DIR"' EXIT HUP INT TERM
    "$CONTENTS_DIR/MacOS/ContinuityBridge" --boot-smoke >"$SMOKE_DIR/output.txt" 2>&1 &
    SMOKE_PID=$!
    COUNT=0
    while kill -0 "$SMOKE_PID" 2>/dev/null; do
        COUNT=$((COUNT + 1))
        if [ "$COUNT" -ge 50 ]; then
            kill "$SMOKE_PID" 2>/dev/null || true
            wait "$SMOKE_PID" 2>/dev/null || true
            echo "boot smoke timed out" >&2
            exit 67
        fi
        sleep 0.1
    done
    wait "$SMOKE_PID"
    rg -q -x 'BOOT_SMOKE_EXECUTABLE_STARTED=1' "$SMOKE_DIR/output.txt"
    rg -q -x 'BOOT_SMOKE_MENU_HOST_INITIALIZED=1' "$SMOKE_DIR/output.txt"
    rg -q -x 'BOOT_SMOKE_STATUS=service_stopped' "$SMOKE_DIR/output.txt"
    cat "$SMOKE_DIR/output.txt"
    rm -rf "$SMOKE_DIR"
    trap - EXIT HUP INT TERM
fi

printf 'APP=%s\nZIP=%s\nSHA256=%s\nTRUST_MODE=%s\n' "$APP_DIR" "$OUTPUT_ZIP" "$HASH" "$TRUST_MODE"
