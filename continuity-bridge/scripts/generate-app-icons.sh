#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ICON_SOURCE="$BRIDGE_DIR/assets/app-icon.png"
ANDROID_RES="$BRIDGE_DIR/android/app/src/main/res"

test -f "$ICON_SOURCE"
test "$(sips -g hasAlpha "$ICON_SOURCE" | awk '/hasAlpha:/ { print $2 }')" = "yes"
mkdir -p "$BRIDGE_DIR/macos/.build"
ICON_WORK=$(mktemp -d "$BRIDGE_DIR/macos/.build/app-icon.XXXXXX")
trap 'rm -rf "$ICON_WORK"' EXIT HUP INT TERM
ICONSET="$ICON_WORK/AppIcon.iconset"
mkdir -p "$ICONSET"

for ICON_SIZE in 16 32 128 256 512; do
    sips -z "$ICON_SIZE" "$ICON_SIZE" "$ICON_SOURCE" \
        --out "$ICONSET/icon_${ICON_SIZE}x${ICON_SIZE}.png" >/dev/null
    RETINA_SIZE=$((ICON_SIZE * 2))
    sips -z "$RETINA_SIZE" "$RETINA_SIZE" "$ICON_SOURCE" \
        --out "$ICONSET/icon_${ICON_SIZE}x${ICON_SIZE}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$BRIDGE_DIR/macos/Packaging/AppIcon.icns"

while read -r DENSITY LEGACY_SIZE FOREGROUND_SIZE; do
    mkdir -p "$ANDROID_RES/mipmap-$DENSITY"
    sips -z "$LEGACY_SIZE" "$LEGACY_SIZE" "$ICON_SOURCE" \
        --out "$ANDROID_RES/mipmap-$DENSITY/ic_launcher.png" >/dev/null
    sips -z "$FOREGROUND_SIZE" "$FOREGROUND_SIZE" "$ICON_SOURCE" \
        --out "$ANDROID_RES/mipmap-$DENSITY/ic_launcher_foreground.png" >/dev/null
done <<'SIZES'
mdpi 48 108
hdpi 72 162
xhdpi 96 216
xxhdpi 144 324
xxxhdpi 192 432
SIZES

printf 'Generated macOS ICNS and Android launcher icon densities from %s\n' "$ICON_SOURCE"
