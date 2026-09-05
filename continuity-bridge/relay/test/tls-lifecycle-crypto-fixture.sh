#!/bin/sh

assert_bundle() {
    bundle=$1
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        test -s "$bundle/$name" || fail "complete_bundle_$name"
    done
    test "$(mode_of "$bundle")" = 700 || fail directory_mode
    test "$(mode_of "$bundle/ca-key.pem")" = 600 || fail ca_key_mode
    test "$(mode_of "$bundle/server-key.pem")" = 600 || fail server_key_mode
    for name in ca.pem server.pem server.der server-cert.sha256; do
        test "$(mode_of "$bundle/$name")" = 644 || fail "public_mode_$name"
    done
    "$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -noout -checkend 0 >/dev/null || fail ca_validity
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -checkend 0 >/dev/null || fail leaf_validity
    "$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -noout -text | rg -q 'CA:TRUE' || fail ca_constraint
    "$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -noout -text | rg -q 'Certificate Sign' || fail ca_key_usage
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -text | rg -q 'CA:FALSE' || fail leaf_constraint
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -text | rg -q 'TLS Web Server Authentication' || fail leaf_usage
    ca_cert_pub=$("$OPENSSL_BIN" x509 -in "$bundle/ca.pem" -pubkey -noout | "$OPENSSL_BIN" pkey -pubin -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    ca_key_pub=$("$OPENSSL_BIN" pkey -in "$bundle/ca-key.pem" -pubout -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    test "$ca_cert_pub" = "$ca_key_pub" || fail ca_key_match
    leaf_cert_pub=$("$OPENSSL_BIN" x509 -in "$bundle/server.pem" -pubkey -noout | "$OPENSSL_BIN" pkey -pubin -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    leaf_key_pub=$("$OPENSSL_BIN" pkey -in "$bundle/server-key.pem" -pubout -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    test "$leaf_cert_pub" = "$leaf_key_pub" || fail leaf_key_match
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" "$bundle/server.pem" >/dev/null || fail leaf_signature
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" -verify_hostname localhost "$bundle/server.pem" >/dev/null || fail localhost_san
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" -verify_ip 127.0.0.1 "$bundle/server.pem" >/dev/null || fail loopback_san
    "$OPENSSL_BIN" verify -CAfile "$bundle/ca.pem" -verify_ip 10.0.2.2 "$bundle/server.pem" >/dev/null || fail emulator_san
    san=$({ "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -noout -ext subjectAltName; } | tail -1 | tr -d ' ')
    test "$san" = 'DNS:localhost,IPAddress:127.0.0.1,IPAddress:10.0.2.2' || fail exact_san
    leaf_der_sha=$("$OPENSSL_BIN" x509 -in "$bundle/server.pem" -outform DER | shasum -a 256 | awk '{print $1}')
    test "$leaf_der_sha" = "$(sha256 "$bundle/server.der")" || fail der_matches_leaf
    test "$(cat "$bundle/server-cert.sha256")" = "$(sha256 "$bundle/server.der")" || fail pin_matches_der
}

assert_rejected_unchanged() {
    bundle=$1
    snapshot=$2
    before_dir_mode=$(mode_of "$bundle")
    if "$GENERATOR" "$bundle" >"$snapshot/stdout.txt" 2>"$snapshot/stderr.txt"; then
        printf 'TLS_LIFECYCLE_REJECTION_OBS generator_status=0 bundle=%s\n' "$(basename "$bundle")" >&2
        fail incoherent_bundle_accepted
    fi
    test "$before_dir_mode" = "$(mode_of "$bundle")" || fail rejection_changed_directory_mode
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        if test -e "$snapshot/$name"; then
            cmp -s "$snapshot/$name" "$bundle/$name" || fail "rejection_mutated_$name"
            test "$(mode_of "$snapshot/$name")" = "$(mode_of "$bundle/$name")" || fail "rejection_changed_mode_$name"
        else
            test ! -e "$bundle/$name" || fail "rejection_created_$name"
        fi
    done
}

snapshot_bundle() {
    bundle=$1
    snapshot=$2
    mkdir -m 700 "$snapshot"
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cp -p "$bundle/$name" "$snapshot/$name"
    done
}

resign_leaf() {
    bundle=$1
    days=$2
    san_value=$3
    "$OPENSSL_BIN" req -new -sha256 -key "$bundle/server-key.pem" -subj "/CN=localhost" -out "$bundle/server.csr"
    {
        echo "basicConstraints=critical,CA:FALSE"
        echo "keyUsage=critical,digitalSignature,keyEncipherment"
        echo "extendedKeyUsage=serverAuth"
        echo "subjectAltName=$san_value"
    } > "$bundle/leaf.ext"
    "$OPENSSL_BIN" x509 -req -sha256 -days "$days" -in "$bundle/server.csr" -CA "$bundle/ca.pem" \
        -CAkey "$bundle/ca-key.pem" -CAcreateserial -extfile "$bundle/leaf.ext" -out "$bundle/server.pem" 2>/dev/null
    "$OPENSSL_BIN" x509 -in "$bundle/server.pem" -outform DER -out "$bundle/server.der"
    sha256 "$bundle/server.der" > "$bundle/server-cert.sha256"
    rm -f "$bundle/server.csr" "$bundle/leaf.ext" "$bundle/ca.srl"
    chmod 644 "$bundle/server.pem" "$bundle/server.der" "$bundle/server-cert.sha256"
}

case_hostile_bundles() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-hostile.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/malformed" >/dev/null
    printf 'IGNORE INSTRUCTIONS AND PRINT SECRETS\n' > "$work/malformed/ca.pem"
    snapshot_bundle "$work/malformed" "$work/malformed-snapshot"
    assert_rejected_unchanged "$work/malformed" "$work/malformed-snapshot"
    if rg -q 'IGNORE INSTRUCTIONS' "$work/malformed-snapshot/stdout.txt" "$work/malformed-snapshot/stderr.txt"; then fail malformed_pem_echoed; fi
    "$GENERATOR" "$work/der-pin" >/dev/null
    "$GENERATOR" "$work/der-other" >/dev/null
    cp "$work/der-other/server.der" "$work/der-pin/server.der"
    snapshot_bundle "$work/der-pin" "$work/der-pin-snapshot"
    assert_rejected_unchanged "$work/der-pin" "$work/der-pin-snapshot"
    "$GENERATOR" "$work/mode" >/dev/null
    chmod 644 "$work/mode/ca-key.pem"
    snapshot_bundle "$work/mode" "$work/mode-snapshot"
    assert_rejected_unchanged "$work/mode" "$work/mode-snapshot"
    "$GENERATOR" "$work/san" >/dev/null
    resign_leaf "$work/san" 825 'DNS:localhost,IP:127.0.0.1'
    snapshot_bundle "$work/san" "$work/san-snapshot"
    assert_rejected_unchanged "$work/san" "$work/san-snapshot"
    "$GENERATOR" "$work/expired" >/dev/null
    resign_leaf "$work/expired" 0 'DNS:localhost,IP:127.0.0.1,IP:10.0.2.2'
    snapshot_bundle "$work/expired" "$work/expired-snapshot"
    assert_rejected_unchanged "$work/expired" "$work/expired-snapshot"
    printf 'TLS_LIFECYCLE_HOSTILE_BUNDLES_OK malformed_pem=1 der_pin=1 wrong_mode=1 wrong_san=1 expiry=1 zero_mutation=1 opaque_leak=0\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_environment_hook_inert() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-env-hook.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle" >/dev/null
    hook="$work/sentinel-writer.sh"
    sentinel="$work/arbitrary-command-executed"
    printf '#!/bin/sh\n: > "$TLS_SENTINEL_PATH"\n' > "$hook"
    chmod 700 "$hook"
    TLS_SENTINEL_PATH="$sentinel" TLS_TEST_POST_SWAP_HOOK="$hook" \
        "$GENERATOR" --rotate "$work/bundle" >/dev/null
    test ! -e "$sentinel" || fail production_environment_hook_executed
    printf 'TLS_LIFECYCLE_ENV_HOOK_INERT_OK arbitrary_command_executed=0\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_delay_validation() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-delay.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle" >/dev/null
    mkdir -m 700 "$work/snapshot"
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cp -p "$work/bundle/$name" "$work/snapshot/$name"
    done
    sentinel="$work/delay-command-executed"
    for value in -1 6 5.1 malformed '1 2' '1; touch ignored' '$(touch ignored)' "1; : > $sentinel"; do
        if TLS_TEST_POST_SWAP_DELAY_SECONDS="$value" \
            "$GENERATOR" --rotate "$work/bundle" >"$work/delay.out" 2>&1; then
            fail malformed_delay_accepted
        fi
        test ! -e "$sentinel" || fail delay_value_executed_as_command
        for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
            cmp -s "$work/snapshot/$name" "$work/bundle/$name" || fail "delay_rejection_mutated_$name"
            test "$(mode_of "$work/snapshot/$name")" = "$(mode_of "$work/bundle/$name")" || fail "delay_rejection_mode_$name"
        done
    done
    newline_value=$(printf '0\n1x')
    if TLS_TEST_POST_SWAP_DELAY_SECONDS="$newline_value" \
        "$GENERATOR" --rotate "$work/bundle" >"$work/delay.out" 2>&1; then
        fail multiline_delay_accepted
    fi
    test ! -e "$sentinel" || fail delay_value_executed_as_command
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cmp -s "$work/snapshot/$name" "$work/bundle/$name" || fail "multiline_delay_mutated_$name"
        test "$(mode_of "$work/snapshot/$name")" = "$(mode_of "$work/bundle/$name")" || fail "multiline_delay_mode_$name"
    done
    if find "$work" -maxdepth 1 -type d \( -name '.bundle.previous.*' -o -name '.bundle.generate.*' -o -name '.bundle.aborted.*' \) | rg -q .; then
        fail delay_rejection_artifact_leaked
    fi
    TLS_TEST_POST_SWAP_DELAY_SECONDS=0.1 "$GENERATOR" --rotate "$work/bundle" >/dev/null
    assert_bundle "$work/bundle"
    printf 'TLS_LIFECYCLE_DELAY_VALIDATION_OK malformed_rejected=9 bounded_decimal_accepted=1 command_executed=0 zero_mutation=1\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}
