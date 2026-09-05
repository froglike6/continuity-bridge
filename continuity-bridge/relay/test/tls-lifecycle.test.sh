#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
GENERATOR="$ROOT/relay/scripts/generate-local-tls.sh"
OPENSSL_BIN=${OPENSSL_BIN:-/opt/homebrew/bin/openssl}
if [ ! -x "$OPENSSL_BIN" ]; then OPENSSL_BIN=$(command -v openssl); fi

fail() {
    echo "TLS_LIFECYCLE_TEST_FAIL case=$1" >&2
    exit 1
}

sha256() {
    shasum -a 256 "$1" | awk '{print $1}'
}

mode_of() {
    stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

. "$ROOT/relay/test/tls-lifecycle-crypto-fixture.sh"

case_baseline() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-baseline.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle"
    assert_bundle "$work/bundle"
    printf 'TLS_LIFECYCLE_BASELINE_OK ca_sha256=%s leaf_sha256=%s pin=%s\n' \
        "$(sha256 "$work/bundle/ca.pem")" "$(sha256 "$work/bundle/server.der")" \
        "$(cat "$work/bundle/server-cert.sha256")"
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_reuse() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-reuse.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle" >/dev/null
    before_ca=$(sha256 "$work/bundle/ca.pem")
    before_leaf=$(sha256 "$work/bundle/server.der")
    before_pin=$(cat "$work/bundle/server-cert.sha256")
    "$GENERATOR" "$work/bundle" >/dev/null
    after_ca=$(sha256 "$work/bundle/ca.pem")
    after_leaf=$(sha256 "$work/bundle/server.der")
    after_pin=$(cat "$work/bundle/server-cert.sha256")
    printf 'TLS_LIFECYCLE_REUSE_OBS before_ca=%s after_ca=%s before_leaf=%s after_leaf=%s before_pin=%s after_pin=%s\n' \
        "$before_ca" "$after_ca" "$before_leaf" "$after_leaf" "$before_pin" "$after_pin"
    test "$before_ca" = "$after_ca" || fail ordinary_rerun_ca_changed
    test "$before_leaf" = "$after_leaf" || fail ordinary_rerun_leaf_changed
    test "$before_pin" = "$after_pin" || fail ordinary_rerun_pin_changed
    printf 'TLS_LIFECYCLE_REUSE_OK ca_sha256=%s leaf_sha256=%s pin=%s\n' "$after_ca" "$after_leaf" "$after_pin"
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_fail_closed() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-failclosed.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM

    mkdir -m 700 "$work/partial" "$work/partial-snapshot"
    printf 'preexisting-ca-placeholder\n' > "$work/partial/ca.pem"
    cp "$work/partial/ca.pem" "$work/partial-snapshot/ca.pem"
    assert_rejected_unchanged "$work/partial" "$work/partial-snapshot"

    "$GENERATOR" "$work/mismatch" >/dev/null
    "$GENERATOR" "$work/other" >/dev/null
    cp "$work/other/ca-key.pem" "$work/mismatch/ca-key.pem"
    mkdir -m 700 "$work/mismatch-snapshot"
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cp -p "$work/mismatch/$name" "$work/mismatch-snapshot/$name"
    done
    assert_rejected_unchanged "$work/mismatch" "$work/mismatch-snapshot"
    printf 'TLS_LIFECYCLE_FAIL_CLOSED_OK partial_unchanged=1 mismatch_unchanged=1\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_android_coherence() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-android.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    mkdir -p "$work/continuity-bridge" "$work/outputs"
    cp -R "$ROOT/android" "$work/continuity-bridge/android"
    mkdir -p "$work/continuity-bridge/runtime/tls"
    cp "$ROOT/runtime/tls/ca.pem" "$work/continuity-bridge/runtime/tls/ca.pem"
    set +e
    "$work/continuity-bridge/android/build.sh" >"$work/build.out" 2>"$work/build.err"
    build_status=$?
    set -e
    test "$build_status" -eq 0 || fail android_build_nonzero_false_green
    mkdir "$work/apk"
    (cd "$work/apk" && unzip -q "$work/outputs/continuity-bridge-android-debug.apk" 'res/raw/continuity_local_ca.pem')
    printf 'TLS_LIFECYCLE_ANDROID_OBS runtime_ca=%s apk_ca=%s\n' \
        "$(sha256 "$work/continuity-bridge/runtime/tls/ca.pem")" \
        "$(sha256 "$work/apk/res/raw/continuity_local_ca.pem")"
    cmp -s "$work/continuity-bridge/runtime/tls/ca.pem" "$work/apk/res/raw/continuity_local_ca.pem" || fail android_packaged_divergent_ca
    "$GENERATOR" "$work/mismatch-tls" >/dev/null
    cp "$work/mismatch-tls/ca.pem" "$work/continuity-bridge/runtime/tls/ca.pem"
    set +e
    "$work/continuity-bridge/android/build.sh" >"$work/mismatch-build.out" 2>"$work/mismatch-build.err"
    mismatch_status=$?
    set -e
    test "$mismatch_status" -ne 0 || fail android_mismatch_build_succeeded
    printf 'TLS_LIFECYCLE_ANDROID_COHERENCE_OK build_status=%s mismatch_status=%s apk_ca_exact=1\n' "$build_status" "$mismatch_status"
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_path_safety() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-paths.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    (CDPATH= cd -- "$work" && "$GENERATOR" safe/nested/bundle >/dev/null)
    assert_bundle "$work/safe/nested/bundle"

    mkdir -p "$work/nested/a" "$work/outside"
    printf 'outside-marker\n' > "$work/outside/marker.txt"
    if "$GENERATOR" "$work/nested/a/../../escaped" >"$work/traversal.out" 2>&1; then
        fail traversal_path_accepted
    fi
    test "$(cat "$work/outside/marker.txt")" = outside-marker || fail traversal_mutated_outside_marker
    test ! -e "$work/escaped" || fail traversal_created_escape_target

    mkdir "$work/symlink-outside"
    ln -s "$work/symlink-outside" "$work/symlink-parent"
    if "$GENERATOR" "$work/symlink-parent/bundle" >"$work/symlink.out" 2>&1; then
        fail symlink_parent_accepted
    fi
    test ! -e "$work/symlink-outside/bundle" || fail symlink_created_escape_target
    printf 'TLS_LIFECYCLE_PATH_SAFETY_OK safe_nested=1 traversal_rejected=1 symlink_rejected=1 outside_unchanged=1\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

interrupt_post_swap() {
    signal_name=$1
    work=$2
    snapshot=$3
    TLS_TEST_POST_SWAP_DELAY_SECONDS=2 \
        "$GENERATOR" --rotate "$work/bundle" >"$work/post-swap-$signal_name.out" 2>&1 &
    child=$!
    attempts=0
    while ! find "$work" -maxdepth 1 -type d -name '.bundle.previous.*' | rg -q .; do
        if ! kill -0 "$child" 2>/dev/null; then fail post_swap_window_not_available; fi
        attempts=$((attempts + 1))
        [ "$attempts" -lt 200 ] || fail post_swap_window_timeout
        sleep 0.02
    done
    kill "-$signal_name" "$child"
    if wait "$child"; then fail post_swap_signal_succeeded; fi
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cmp -s "$snapshot/$name" "$work/bundle/$name" || fail "post_swap_${signal_name}_changed_$name"
        test "$(mode_of "$snapshot/$name")" = "$(mode_of "$work/bundle/$name")" || fail "post_swap_${signal_name}_mode_$name"
    done
    if find "$work" -maxdepth 1 -type d \( -name '.bundle.previous.*' -o -name '.bundle.generate.*' \) | rg -q .; then
        fail post_swap_artifact_leaked
    fi
}

case_post_swap_signals() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-postswap.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle" >/dev/null
    mkdir -m 700 "$work/snapshot"
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cp -p "$work/bundle/$name" "$work/snapshot/$name"
    done
    interrupt_post_swap TERM "$work" "$work/snapshot"
    interrupt_post_swap HUP "$work" "$work/snapshot"
    "$GENERATOR" "$work/bundle" >/dev/null
    assert_bundle "$work/bundle"
    printf 'TLS_LIFECYCLE_POST_SWAP_SIGNAL_OK term_restored=1 hup_restored=1 artifacts=0 commit_boundary=pre_commit\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_rotate() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-rotate.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle" >/dev/null
    before_ca=$(sha256 "$work/bundle/ca.pem")
    before_leaf=$(sha256 "$work/bundle/server.der")
    "$GENERATOR" --rotate "$work/bundle" >/dev/null
    assert_bundle "$work/bundle"
    after_ca=$(sha256 "$work/bundle/ca.pem")
    after_leaf=$(sha256 "$work/bundle/server.der")
    test "$before_ca" != "$after_ca" || fail explicit_rotation_ca_unchanged
    test "$before_leaf" != "$after_leaf" || fail explicit_rotation_leaf_unchanged
    printf 'TLS_LIFECYCLE_ROTATE_OK ca_sha256=%s leaf_sha256=%s pin=%s\n' \
        "$after_ca" "$after_leaf" "$(cat "$work/bundle/server-cert.sha256")"
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case_interruptions() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/continuity-tls-interrupt.XXXXXX")
    trap 'rm -rf "$work"' EXIT HUP INT TERM
    "$GENERATOR" "$work/bundle" >/dev/null
    mkdir -m 700 "$work/snapshot"
    for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
        cp "$work/bundle/$name" "$work/snapshot/$name"
    done
    count=1
    while [ "$count" -le 2 ]; do
        OPENSSL_BIN="$ROOT/relay/test/tls-slow-openssl-fixture.sh" \
            "$GENERATOR" --rotate "$work/bundle" >"$work/interrupt-$count.out" 2>&1 &
        child=$!
        attempts=0
        while ! find "$work" -maxdepth 1 -type d -name '.bundle.generate.*' | rg -q .; do
            attempts=$((attempts + 1))
            [ "$attempts" -lt 100 ] || fail interrupt_stage_timeout
            sleep 0.02
        done
        kill -TERM "$child"
        if wait "$child"; then fail interrupted_rotation_succeeded; fi
        for name in ca-key.pem ca.pem server-key.pem server.pem server.der server-cert.sha256; do
            cmp -s "$work/snapshot/$name" "$work/bundle/$name" || fail "interrupt_mutated_$name"
        done
        if find "$work" -maxdepth 1 -type d -name '.bundle.generate.*' | rg -q .; then
            fail interrupt_stage_leaked
        fi
        count=$((count + 1))
    done
    "$GENERATOR" "$work/bundle" >/dev/null
    assert_bundle "$work/bundle"
    printf 'TLS_LIFECYCLE_INTERRUPTION_OK attempts=2 canonical_unchanged=1 resume_valid=1\n'
    rm -rf "$work"
    trap - EXIT HUP INT TERM
}

case ${1:-all} in
    baseline) case_baseline ;;
    reuse) case_reuse ;;
    fail-closed) case_fail_closed ;;
    hostile-bundles) case_hostile_bundles ;;
    android-coherence) case_android_coherence ;;
    path-safety) case_path_safety ;;
    environment-hook-inert) case_environment_hook_inert ;;
    delay-validation) case_delay_validation ;;
    post-swap-signals) case_post_swap_signals ;;
    rotate) case_rotate ;;
    interruptions) case_interruptions ;;
    all) case_baseline; case_reuse; case_fail_closed; case_hostile_bundles; case_android_coherence; case_path_safety; case_environment_hook_inert; case_delay_validation; case_post_swap_signals; case_rotate; case_interruptions ;;
    *) echo "usage: $0 [baseline|reuse|fail-closed|hostile-bundles|android-coherence|path-safety|environment-hook-inert|delay-validation|post-swap-signals|rotate|interruptions|all]" >&2; exit 64 ;;
esac
