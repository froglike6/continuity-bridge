package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StateEncryptionSuite {
    private static final String CLIPBOARD = "STATE_CLIPBOARD_SENTINEL_4f26";
    private static final String TITLE = "STATE_NOTIFICATION_TITLE_8c19";
    private static final String BODY = "STATE_NOTIFICATION_BODY_63da";
    private static int cases;

    private StateEncryptionSuite() { }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        exactEncryptedRestart(root.resolve("exact.db"));
        nonceAndCiphertextChange(root.resolve("fresh-nonce.db"));
        rejectionCases(root);
        encryptedBackupRecovery(root.resolve("backup.db"));
        staleTempRecovery(root.resolve("stale-temp.db"));
        System.out.println("STATE_ENCRYPTION_OK cases=" + cases
                + " markers=exact_restart,raw_absent,recursive_base64_absent,fresh_nonce,tamper_closed,truncation_closed,wrong_key_closed,version_closed,legacy_plaintext_closed,encrypted_backup_recovery,stale_temp_recovery,retry_equivalence");
    }

    private static void exactEncryptedRestart(Path path) throws Exception {
        TestStateCipher cipher = TestStateCipher.create();
        AtomicStateFile firstProcess = new AtomicStateFile(path, cipher);
        BridgeState original = fixtureState();
        firstProcess.save(original);
        byte[] bytes = Files.readAllBytes(path);
        check(!containsSentinel(bytes), "raw sentinel recovered"); cases++;
        check(!recursiveBase64Recovery(bytes, 0), "Base64 sentinel recovered"); cases++;
        BridgeState restored = new AtomicStateFile(path, cipher).load();
        check(equivalent(original, restored), "restart state mismatch"); cases++;
        check(restored.enqueue(restored.outbox().get(0)) == restored && restored.outbox().size() == 2,
                "restart retry changed queued event"); cases++;
        System.out.println("STATE_CONFIDENTIALITY_OBSERVED raw=false recursive_base64=false bytes=" + bytes.length
                + " sha256=" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)) + " path=" + path);
    }

    private static void nonceAndCiphertextChange(Path path) throws Exception {
        AtomicStateFile file = new AtomicStateFile(path, TestStateCipher.create());
        file.save(fixtureState()); byte[] first = Files.readAllBytes(path);
        file.save(fixtureState()); byte[] second = Files.readAllBytes(path);
        check(!Arrays.equals(first, second), "ciphertext repeated"); cases++;
        check(!Arrays.equals(Arrays.copyOfRange(first, 6, 18), Arrays.copyOfRange(second, 6, 18)), "nonce repeated"); cases++;
    }

    private static void rejectionCases(Path root) throws Exception {
        byte[] sealed = singleGeneration(root.resolve("tamper.db"));
        byte[] tampered = sealed.clone(); tampered[tampered.length - 1] ^= 1;
        rejectAndPreserve(root.resolve("tamper.db"), TestStateCipher.create(), tampered, "tamper");
        rejectAndPreserve(root.resolve("truncated.db"), TestStateCipher.create(), Arrays.copyOf(sealed, 17), "truncation");
        Path wrongKey = root.resolve("wrong-key.db"); singleGeneration(wrongKey);
        rejectExistingAndPreserve(wrongKey, TestStateCipher.otherKey(), "wrong key");
        byte[] invalidVersion = sealed.clone(); invalidVersion[4] = 2;
        rejectAndPreserve(root.resolve("version.db"), TestStateCipher.create(), invalidVersion, "version");
        Path legacy = root.resolve("legacy.db");
        byte[] plaintext = ("continuity-state-v1\nevent=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(CLIPBOARD.getBytes(StandardCharsets.UTF_8)) + "\n").getBytes(StandardCharsets.UTF_8);
        rejectAndPreserve(legacy, TestStateCipher.create(), plaintext, "legacy plaintext");
    }

    private static byte[] singleGeneration(Path path) throws Exception {
        AtomicStateFile file = new AtomicStateFile(path, TestStateCipher.create());
        file.save(fixtureState());
        return Files.readAllBytes(path);
    }

    private static void rejectAndPreserve(Path path, StateCipher cipher, byte[] bytes, String name) throws Exception {
        Files.write(path, bytes);
        rejectExistingAndPreserve(path, cipher, name);
    }

    private static void rejectExistingAndPreserve(Path path, StateCipher cipher, String name) throws Exception {
        byte[] before = Files.readAllBytes(path);
        try {
            new AtomicStateFile(path, cipher).load();
            throw new AssertionError(name + " accepted");
        } catch (CorruptStateException expected) {
            check(Arrays.equals(before, Files.readAllBytes(path)), name + " changed original"); cases++;
        }
    }

    private static void encryptedBackupRecovery(Path path) throws Exception {
        TestStateCipher cipher = TestStateCipher.create();
        AtomicStateFile file = new AtomicStateFile(path, cipher);
        BridgeState first = fixtureState();
        file.save(first); file.save(first.cursor("new-cursor"));
        byte[] damaged = Files.readAllBytes(path); damaged[damaged.length - 1] ^= 1; Files.write(path, damaged);
        byte[] beforeLoad = Files.readAllBytes(path);
        check(equivalent(first, file.load()), "encrypted backup mismatch"); cases++;
        check(Arrays.equals(beforeLoad, Files.readAllBytes(path)), "backup recovery changed damaged current"); cases++;
    }

    private static void staleTempRecovery(Path path) throws Exception {
        TestStateCipher cipher = TestStateCipher.create();
        AtomicStateFile file = new AtomicStateFile(path, cipher);
        BridgeState original = fixtureState(); file.save(original);
        Path stale = path.resolveSibling(path.getFileName() + ".new");
        Files.writeString(stale, "continuity-state-v1\n" + CLIPBOARD);
        file.save(original.cursor("after-stale-temp"));
        check(!Files.exists(stale) && "after-stale-temp".equals(file.load().cursor()), "stale temp recovery failed"); cases++;
    }

    private static BridgeState fixtureState() {
        BridgeState state = BridgeState.fresh("android-state-device", "android-state-epoch", 8, 8);
        state = state.enqueue(event("clipboard-event-fixed", 1, "android", "android-state-epoch", "clipboard.text", map("text", CLIPBOARD)));
        Map<String, String> notification = map("notificationKey", "notification-key-fixed");
        notification.put("packageName", "com.example.fixture"); notification.put("appLabel", "Fixture");
        notification.put("title", TITLE); notification.put("body", BODY);
        state = state.enqueue(event("notification-event-fixed", 2, "android", "android-state-epoch", "android.notification", notification));
        state = state.applied(event("macos-event-fixed", 9, "macos", "macos-state-epoch", "clipboard.text", map("text", "remote")), "relay-cursor-41");
        return state.relay("relay-epoch-7", "relay-cursor-41");
    }

    private static ProtocolEvent event(String id, long sequence, String role, String epoch, String kind, Map<String, String> payload) {
        String device = "android".equals(role) ? "android-state-device" : "macos-state-device";
        return new ProtocolEvent(id, device, role, epoch, sequence, kind, 1_000L + sequence, null, payload);
    }

    private static boolean equivalent(BridgeState first, BridgeState second) {
        if (!first.deviceId().equals(second.deviceId()) || !first.epoch().equals(second.epoch())
                || first.nextSequence() != second.nextSequence() || !first.cursor().equals(second.cursor())
                || !first.serverEpoch().equals(second.serverEpoch()) || !first.appliedIds().equals(second.appliedIds())
                || !first.pendingAcks().equals(second.pendingAcks()) || !first.highWater().equals(second.highWater())
                || !first.fingerprints().equals(second.fingerprints()) || !equal(first.remoteApplyId(), second.remoteApplyId())
                || !equal(first.remoteApplyObservationIdentity(), second.remoteApplyObservationIdentity())
                || first.outbox().size() != second.outbox().size()) return false;
        for (int index = 0; index < first.outbox().size(); index++) {
            if (!EventCodec.encode(first.outbox().get(index)).equals(EventCodec.encode(second.outbox().get(index)))) return false;
        }
        return true;
    }

    private static boolean recursiveBase64Recovery(byte[] bytes, int depth) {
        if (depth > 5) return false;
        String value = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String token : value.split("[^A-Za-z0-9_\\-+/]+")) {
            if (token.length() < 8) continue;
            for (Base64.Decoder decoder : new Base64.Decoder[] { Base64.getDecoder(), Base64.getUrlDecoder() }) {
                try {
                    byte[] decoded = decoder.decode(pad(token));
                    if (containsSentinel(decoded) || recursiveBase64Recovery(decoded, depth + 1)) return true;
                } catch (IllegalArgumentException invalid) { }
            }
        }
        return false;
    }

    private static boolean containsSentinel(byte[] bytes) {
        return contains(bytes, CLIPBOARD) || contains(bytes, TITLE) || contains(bytes, BODY);
    }

    private static boolean contains(byte[] bytes, String sentinel) {
        byte[] needle = sentinel.getBytes(StandardCharsets.UTF_8);
        outer: for (int index = 0; index <= bytes.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) if (bytes[index + offset] != needle[offset]) continue outer;
            return true;
        }
        return false;
    }

    private static Map<String, String> map(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>(); values.put(key, value); return values;
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) return value;
        StringBuilder padded = new StringBuilder(value);
        for (int index = remainder; index < 4; index++) padded.append('=');
        return padded.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static boolean equal(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
