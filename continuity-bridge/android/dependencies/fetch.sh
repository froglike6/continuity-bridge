#!/bin/sh
set -eu
DEPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=${1:?dependency destination required}
CACHE="$DEPS_DIR/.cache"
mkdir -p "$CACHE" "$DEST"
while read -r expected url; do
    name=${url##*/}
    artifact="$CACHE/$name"
    if ! test -s "$artifact"; then
        temporary="$artifact.new.$$"
        trap 'rm -f "$temporary"' EXIT INT TERM
        curl --fail --location --silent --show-error --connect-timeout 10 --max-time 30 "$url" -o "$temporary"
        actual=$(shasum -a 256 "$temporary" | awk '{print $1}')
        test "$actual" = "$expected" || { echo "Dependency checksum mismatch: $name" >&2; exit 1; }
        mv "$temporary" "$artifact"
        trap - EXIT INT TERM
    fi
    actual=$(shasum -a 256 "$artifact" | awk '{print $1}')
    test "$actual" = "$expected" || { echo "Cached dependency checksum mismatch: $name" >&2; exit 1; }
    case "$name" in
        *.aar) unzip -p "$artifact" classes.jar > "$DEST/${name%.aar}.jar" ;;
        *.jar) cp "$artifact" "$DEST/$name" ;;
        *) echo "Unsupported dependency: $name" >&2; exit 1 ;;
    esac
done < "$DEPS_DIR/shizuku.lock"
