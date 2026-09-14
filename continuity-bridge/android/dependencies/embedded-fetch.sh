#!/bin/sh
set -eu
DEPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=${1:?dependency destination required}
CACHE=${CONTINUITY_EMBEDDED_CACHE:-"$DEPS_DIR/.embedded-cache"}
mkdir -p "$CACHE" "$DEST"
STAGE=$(mktemp -d "$DEST/.embedded-stage.XXXXXX")
temporary=
trap 'test -z "$temporary" || rm -f "$temporary"; rm -rf "$STAGE"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$STAGE/jars" "$STAGE/lib" "$STAGE/sources" "$STAGE/metadata" "$STAGE/resources" "$STAGE/assets/licenses/embedded"
: > "$STAGE/runtime-jars.txt"
: > "$STAGE/compile-only-jars.txt"
while read -r expected kind name url extra; do
    case "$expected" in ''|'#'*) continue ;; esac
    test "${#expected}" = 64 || { echo "Invalid embedded checksum" >&2; exit 1; }
    case "$expected" in *[!0-9a-f]*) echo "Invalid embedded checksum" >&2; exit 1 ;; esac
    test -z "$extra" || { echo "Invalid embedded lock row" >&2; exit 1; }
    case "$name" in ''|.*|*[!A-Za-z0-9._-]*) echo "Invalid embedded artifact name" >&2; exit 1 ;; esac
    case "$url" in https://*) ;; *) echo "Embedded dependency requires HTTPS" >&2; exit 1 ;; esac
    case "$kind:$name" in runtime-aar:*.aar|runtime-jar:*.jar|compile-jar:*.jar|source:*.tar.gz|source:*.jar|metadata:*.pom) ;;
        *) echo "Unsupported embedded artifact: $kind $name" >&2; exit 1 ;;
    esac
    artifact="$CACHE/$name"
    if ! test -e "$artifact"; then
        temporary=$(mktemp "$CACHE/$name.new.XXXXXX")
        curl --fail --location --silent --show-error --connect-timeout 10 --max-time 60 "$url" -o "$temporary"
        actual=$(shasum -a 256 "$temporary" | awk '{print $1}')
        test "$actual" = "$expected" || { echo "Embedded dependency checksum mismatch: $name" >&2; exit 1; }
        mv "$temporary" "$artifact"
        temporary=
    fi
    actual=$(shasum -a 256 "$artifact" | awk '{print $1}')
    test "$actual" = "$expected" || { echo "Cached embedded dependency checksum mismatch: $name" >&2; exit 1; }
    case "$kind" in
        runtime-aar)
            jar="${name%.aar}.jar"
            unzip -p "$artifact" classes.jar > "$STAGE/jars/$jar"
            printf 'jars/%s\n' "$jar" >> "$STAGE/runtime-jars.txt"
            native=
            case "$name" in
                spake2-android-*) native=libspake2.so ;;
                conscrypt-android-*)
                    native=libconscrypt_jni.so
                    unzip -q "$STAGE/jars/$jar" org/conscrypt/conscrypt.properties -d "$STAGE/resources"
                    ;;
            esac
            if test -n "$native"; then
                for abi in arm64-v8a x86_64; do
                    mkdir -p "$STAGE/lib/$abi"
                    unzip -p "$artifact" "jni/$abi/$native" > "$STAGE/lib/$abi/$native"
                    test -s "$STAGE/lib/$abi/$native"
                done
            fi
            ;;
        runtime-jar)
            cp "$artifact" "$STAGE/jars/$name"
            unzip -q "$artifact" "org/bouncycastle/*.properties" "META-INF/services/*" -d "$STAGE/resources"
            printf 'jars/%s\n' "$name" >> "$STAGE/runtime-jars.txt"
            ;;
        compile-jar)
            cp "$artifact" "$STAGE/jars/$name"
            printf 'jars/%s\n' "$name" >> "$STAGE/compile-only-jars.txt"
            ;;
        source) cp "$artifact" "$STAGE/sources/$name" ;;
        metadata) cp "$artifact" "$STAGE/metadata/$name" ;;
    esac
done < "$DEPS_DIR/embedded.lock"
if test -f "$DEPS_DIR/patches/PairingConnectionCtx.java"; then
    patched_jar="$STAGE/jars/libadb-android-3.1.1.jar"
    unzip -Z1 "$patched_jar" > "$STAGE/libadb-classes.txt"
    awk '$0 == "io/github/muntashirakon/adb/PairingConnectionCtx.class" { found=1 } END { exit !found }' "$STAGE/libadb-classes.txt"
    zip -q -d "$patched_jar" 'io/github/muntashirakon/adb/PairingConnectionCtx*.class'
    unzip -Z1 "$patched_jar" > "$STAGE/libadb-classes.txt"
    awk '/^io\/github\/muntashirakon\/adb\/PairingConnectionCtx.*\.class$/ { found=1 } END { exit found }' "$STAGE/libadb-classes.txt"
    cp "$DEPS_DIR/patches/PairingConnectionCtx.java" "$STAGE/sources/PairingConnectionCtx.java"
    rm "$STAGE/libadb-classes.txt"
fi
cp "$DEPS_DIR/embedded.lock" "$STAGE/metadata/embedded.lock"
cp "$DEPS_DIR/embedded-README.md" "$DEPS_DIR/embedded-native-alignment.txt" "$STAGE/metadata/"
cp "$DEPS_DIR/embedded-licenses/"* "$STAGE/assets/licenses/embedded/"
cp -R "$STAGE/." "$DEST/"
printf 'EMBEDDED_DEPENDENCIES_OK destination=%s\n' "$DEST"
