#!/bin/sh
set -eu

ROTATE=0
if [ "${1:-}" = "--rotate" ]; then
    ROTATE=1
    shift
fi
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "${1#--}" != "$1" ]; }; then
    echo "usage: $0 [--rotate] [output-dir]" >&2
    exit 64
fi

OUTPUT_DIR=${1:-continuity-bridge/runtime/tls}
PARENT_DIR=$(dirname "$OUTPUT_DIR")
BASE_NAME=$(basename "$OUTPUT_DIR")
case "$OUTPUT_DIR:$BASE_NAME" in
    /:*|.:*|..:*|*:|*:.|*:..) echo "TLS_ERROR unsafe bundle directory" >&2; exit 64 ;;
esac
OPENSSL_BIN=${OPENSSL_BIN:-/opt/homebrew/bin/openssl}
if [ ! -x "$OPENSSL_BIN" ]; then OPENSSL_BIN=$(command -v openssl); fi
FILES="ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256"
PRIVATE_FILES="ca-key.pem server-key.pem"
PUBLIC_FILES="ca.pem server.pem server.der server-cert.sha256"
STAGE_DIR=
BACKUP_DIR=
ABORTED_DIR=
SWAP_STARTED=0
COMMITTED=0

mode_of() {
    stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

sha256() {
    shasum -a 256 "$1" | awk '{print tolower($1)}'
}

fail() {
    echo "TLS_ERROR $1" >&2
    exit 1
}

cleanup() {
    if [ "$COMMITTED" -eq 0 ] && [ "$SWAP_STARTED" -eq 1 ]; then
        if [ -e "$OUTPUT_DIR" ]; then
            ABORTED_DIR="$PARENT_DIR/.${BASE_NAME}.aborted.$$"
            mv "$OUTPUT_DIR" "$ABORTED_DIR"
        fi
        if [ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ]; then
            mv "$BACKUP_DIR" "$OUTPUT_DIR"
            BACKUP_DIR=
        fi
        if [ -n "$ABORTED_DIR" ] && [ -d "$ABORTED_DIR" ]; then rm -rf "$ABORTED_DIR"; fi
    elif [ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ]; then
        rm -rf "$BACKUP_DIR"
    fi
    if [ -n "$STAGE_DIR" ] && [ -d "$STAGE_DIR" ]; then rm -rf "$STAGE_DIR"; fi
}

on_signal() {
    code=$1
    trap - EXIT HUP INT TERM
    cleanup
    exit "$code"
}
trap cleanup EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

validate_output_path() {
    case "/$OUTPUT_DIR/" in
        */./*|*/../*) fail "bundle path contains a dot traversal component" ;;
    esac
    case "$OUTPUT_DIR" in
        /*) current=/; remaining=${OUTPUT_DIR#/}; absolute=1 ;;
        *) current=.; remaining=$OUTPUT_DIR; absolute=0 ;;
    esac
    first=1
    while [ -n "$remaining" ]; do
        component=${remaining%%/*}
        if [ "$remaining" = "$component" ]; then remaining=; else remaining=${remaining#*/}; fi
        [ -n "$component" ] || continue
        candidate=$current/$component
        if [ -L "$candidate" ]; then
            if [ "$absolute" -eq 1 ] && [ "$first" -eq 1 ]; then
                current=$(CDPATH= cd -- "$candidate" 2>/dev/null && pwd -P) || fail "top-level path symlink is invalid"
            else
                fail "bundle path contains a symlink component"
            fi
        else
            current=$candidate
        fi
        first=0
    done
}

validate_bundle() {
    bundle=$1
    [ -d "$bundle" ] || fail "bundle is not a directory"
    [ "$(mode_of "$bundle")" = 700 ] || fail "bundle directory mode must be 700"
    for name in $FILES; do [ -s "$bundle/$name" ] || fail "bundle is incomplete"; done
    for name in $PRIVATE_FILES; do
        [ "$(mode_of "$bundle/$name")" = 600 ] || fail "$name mode must be 600"
    done
    for name in $PUBLIC_FILES; do
        [ "$(mode_of "$bundle/$name")" = 644 ] || fail "$name mode must be 644"
    done

    "$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -noout -checkend 0 >/dev/null 2>&1 || fail "CA certificate is invalid or expired"
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -checkend 0 >/dev/null 2>&1 || fail "server certificate is invalid or expired"
    "$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -noout -text 2>/dev/null | rg -q 'CA:TRUE' || fail "CA basicConstraints are invalid"
    "$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -noout -text 2>/dev/null | rg -q 'Certificate Sign' || fail "CA keyUsage is invalid"
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -text 2>/dev/null | rg -q 'CA:FALSE' || fail "server basicConstraints are invalid"
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -text 2>/dev/null | rg -q 'TLS Web Server Authentication' || fail "server extendedKeyUsage is invalid"

    ca_cert_pub=$("$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -pubkey -noout 2>/dev/null | "$OPENSSL_BIN" pkey -pubin -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    ca_key_pub=$("$OPENSSL_BIN" pkey -in "$bundle/ca-key.pem" -pubout -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    [ "$ca_cert_pub" = "$ca_key_pub" ] || fail "CA certificate and key do not match"
    leaf_cert_pub=$("$OPENSSL_BIN" x509 -in "$bundle/server.pem" -pubkey -noout 2>/dev/null | "$OPENSSL_BIN" pkey -pubin -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    leaf_key_pub=$("$OPENSSL_BIN" pkey -in "$bundle/server-key.pem" -pubout -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    [ "$leaf_cert_pub" = "$leaf_key_pub" ] || fail "server certificate and key do not match"

    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" "$bundle/ca.pem" >/dev/null 2>&1 || fail "CA is not self-verifiable"
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" "$bundle/server.pem" >/dev/null 2>&1 || fail "server certificate is not signed by CA"
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" -verify_hostname localhost "$bundle/server.pem" >/dev/null 2>&1 || fail "localhost SAN is invalid"
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" -verify_ip 127.0.0.1 "$bundle/server.pem" >/dev/null 2>&1 || fail "127.0.0.1 SAN is invalid"
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" -verify_ip 10.0.2.2 "$bundle/server.pem" >/dev/null 2>&1 || fail "10.0.2.2 SAN is invalid"
    san=$({ "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -ext subjectAltName; } 2>/dev/null | tail -1 | tr -d ' ')
    [ "$san" = 'DNS:localhost,IPAddress:127.0.0.1,IPAddress:10.0.2.2' ] || fail "server SAN set is not exact"

    leaf_der_sha=$("$OPENSSL_BIN" x509 -in "$bundle/server.pem" -outform DER 2>/dev/null | shasum -a 256 | awk '{print tolower($1)}')
    [ "$leaf_der_sha" = "$(sha256 "$bundle/server.der")" ] || fail "server DER does not match PEM"
    pin=$(cat "$bundle/server-cert.sha256")
    [ "$(wc -l < "$bundle/server-cert.sha256" | tr -d ' ')" = 1 ] || fail "pin file must contain one line"
    printf '%s\n' "$pin" | rg -q '^[0-9a-f]{64}$' || fail "pin format is invalid"
    [ "$pin" = "$leaf_der_sha" ] || fail "pin does not match server certificate"
}

generate_bundle() {
    bundle=$1
    umask 077
    "$OPENSSL_BIN" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$bundle/ca-key.pem" 2>/dev/null
    "$OPENSSL_BIN" req -x509 -new -sha256 -days 3650 -key "$bundle/ca-key.pem" \
        -subj "/CN=Continuity Bridge Local CA" -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$bundle/ca.pem"
    "$OPENSSL_BIN" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$bundle/server-key.pem" 2>/dev/null
    "$OPENSSL_BIN" req -new -sha256 -key "$bundle/server-key.pem" -subj "/CN=localhost" -out "$bundle/server.csr"
    {
        echo "basicConstraints=critical,CA:FALSE"
        echo "keyUsage=critical,digitalSignature,keyEncipherment"
        echo "extendedKeyUsage=serverAuth"
        echo "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"
    } > "$bundle/leaf.ext"
    "$OPENSSL_BIN" x509 -req -sha256 -days 825 -in "$bundle/server.csr" -CA "$bundle/ca.pem" \
        -CAkey "$bundle/ca-key.pem" -CAcreateserial -extfile "$bundle/leaf.ext" -out "$bundle/server.pem" 2>/dev/null
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -outform DER -out "$bundle/server.der"
    sha256 "$bundle/server.der" > "$bundle/server-cert.sha256"
    rm -f "$bundle/server.csr" "$bundle/leaf.ext" "$bundle/ca.srl"
    chmod 600 "$bundle/ca-key.pem" "$bundle/server-key.pem"
    chmod 644 "$bundle/ca.pem" "$bundle/server.pem" "$bundle/server.der" "$bundle/server-cert.sha256"
}

validate_output_path

if [ "$ROTATE" -eq 0 ] && [ -e "$OUTPUT_DIR" ]; then
    [ ! -L "$OUTPUT_DIR" ] || fail "bundle directory must not be a symlink"
    [ -d "$OUTPUT_DIR" ] || fail "bundle path is not a directory"
    entry_count=$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')
    if [ "$entry_count" -gt 0 ]; then
        validate_bundle "$OUTPUT_DIR"
        printf 'TLS_REUSED pin=%s\n' "$(cat "$OUTPUT_DIR/server-cert.sha256")"
        exit 0
    fi
fi

mkdir -p "$PARENT_DIR"
[ ! -L "$OUTPUT_DIR" ] || fail "bundle directory must not be a symlink"
STAGE_DIR=$(mktemp -d "$PARENT_DIR/.${BASE_NAME}.generate.XXXXXX")
chmod 700 "$STAGE_DIR"
generate_bundle "$STAGE_DIR"
validate_bundle "$STAGE_DIR"

if [ -e "$OUTPUT_DIR" ]; then
    BACKUP_DIR="$PARENT_DIR/.${BASE_NAME}.previous.$$"
    [ ! -e "$BACKUP_DIR" ] || fail "rotation backup path already exists"
    mv "$OUTPUT_DIR" "$BACKUP_DIR"
fi
SWAP_STARTED=1
mv "$STAGE_DIR" "$OUTPUT_DIR"
STAGE_DIR=
if [ -n "${TLS_TEST_POST_SWAP_DELAY_SECONDS:-}" ]; then
    [ "$(printf '%s\n' "$TLS_TEST_POST_SWAP_DELAY_SECONDS" | wc -l | tr -d ' ')" = 1 ] || \
        fail "post-swap delay must be one line"
    printf '%s\n' "$TLS_TEST_POST_SWAP_DELAY_SECONDS" | \
        rg -q '^([0-4](\.[0-9]+)?|5(\.0+)?)$' || fail "post-swap delay must be a decimal from 0 to 5 seconds"
    /bin/sleep "$TLS_TEST_POST_SWAP_DELAY_SECONDS"
fi
COMMITTED=1
if [ -n "$BACKUP_DIR" ]; then rm -rf "$BACKUP_DIR"; BACKUP_DIR=; fi
if [ "$ROTATE" -eq 1 ]; then
    printf 'TLS_ROTATED pin=%s\n' "$(cat "$OUTPUT_DIR/server-cert.sha256")"
else
    printf 'TLS_READY pin=%s\n' "$(cat "$OUTPUT_DIR/server-cert.sha256")"
fi
