package com.froglike6.continuitybridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class AtomicStateFile {
    private static final int MAX_PLAINTEXT_BYTES = 4_000_000;
    private static final int MAX_ENVELOPE_BYTES = 4_000_064;
    public enum Fault { NONE, BEFORE_REPLACE }
    private final Path path;
    private final Fault fault;
    private final StateCipher cipher;
    public AtomicStateFile(Path path, StateCipher cipher) { this(path, cipher, Fault.NONE); }
    public AtomicStateFile(Path path, StateCipher cipher, Fault fault) { this.path = path; this.cipher = cipher; this.fault = fault; }

    public void save(BridgeState state) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("continuity-state-v1");
        lines.add(field("device", state.deviceId())); lines.add(field("epoch", state.epoch()));
        lines.add("sequence=" + state.nextSequence()); lines.add(field("cursor", state.cursor()));
        lines.add(field("serverEpoch", state.serverEpoch()));
        lines.add("outboxLimit=" + state.outboxLimit()); lines.add("appliedLimit=" + state.appliedLimit());
        lines.add(field("marker", state.remoteApplyId() == null ? "" : state.remoteApplyId()));
        lines.add(field("markerObservation", state.remoteApplyObservationIdentity() == null
                ? "" : state.remoteApplyObservationIdentity()));
        for (ProtocolEvent event : state.outbox()) lines.add(field("event", EventCodec.encode(event)));
        for (String id : state.appliedIds()) lines.add(field("applied", id));
        for (String id : state.pendingAcks()) lines.add(field("pending", id));
        for (Map.Entry<String, Long> entry : state.highWater().entrySet()) lines.add(field("water", entry.getKey()) + ":" + entry.getValue());
        for (Map.Entry<String, String> entry : state.fingerprints().entrySet()) lines.add(field("print", entry.getKey()) + ":" + b64(entry.getValue()));
        Path parent = path.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".new");
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        byte[] plaintext = join(lines).getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_PLAINTEXT_BYTES) throw new IOException("state_too_large");
        byte[] encoded;
        try { encoded = cipher.seal(plaintext); }
        catch (GeneralSecurityException error) { throw new IOException("state_encryption_failed", error); }
        if (encoded.length > MAX_ENVELOPE_BYTES) throw new IOException("state_envelope_too_large");
        try {
            writeForced(temp, encoded);
            if (Files.exists(path)) {
                Path backupNew = backup.resolveSibling(backup.getFileName() + ".new");
                try { writeForced(backupNew, Files.readAllBytes(path)); replace(backupNew, backup); }
                finally { Files.deleteIfExists(backupNew); }
            }
            if (fault == Fault.BEFORE_REPLACE) throw new IOException("injected_before_replace");
            replace(temp, path); syncDirectory(parent);
        } finally { Files.deleteIfExists(temp); }
    }

    public BridgeState load() throws IOException {
        try { return load(path); }
        catch (IOException currentError) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            if (!Files.exists(backup)) throw currentError;
            return load(backup);
        }
    }

    private BridgeState load(Path candidate) throws IOException {
        if (Files.size(candidate) > MAX_ENVELOPE_BYTES) throw new CorruptStateException("state_envelope_too_large");
        byte[] plaintext;
        try { plaintext = cipher.open(Files.readAllBytes(candidate)); }
        catch (GeneralSecurityException error) { throw new CorruptStateException("invalid_state_envelope", error); }
        if (plaintext.length > MAX_PLAINTEXT_BYTES) throw new CorruptStateException("state_too_large");
        List<String> lines = java.util.Arrays.asList(new String(plaintext, StandardCharsets.UTF_8).split("\\n"));
        if (lines.isEmpty() || !"continuity-state-v1".equals(lines.get(0))) throw new CorruptStateException("invalid_state_header");
        Map<String, String> singles = new LinkedHashMap<>(); List<ProtocolEvent> events = new ArrayList<>();
        LinkedHashSet<String> applied = new LinkedHashSet<>(); Map<String, Long> water = new LinkedHashMap<>();
        LinkedHashSet<String> pending = new LinkedHashSet<>();
        Map<String, String> prints = new LinkedHashMap<>();
        try {
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index); int equals = line.indexOf('=');
                if (equals < 1) throw new IllegalArgumentException("state_line");
                String key = line.substring(0, equals); String value = line.substring(equals + 1);
                if ("event".equals(key)) events.add(EventCodec.decode(unb64(value)));
                else if ("applied".equals(key)) applied.add(unb64(value));
                else if ("pending".equals(key)) pending.add(unb64(value));
                else if ("water".equals(key)) pairLong(value, water);
                else if ("print".equals(key)) pairString(value, prints);
                else if (singles.put(key, value) != null) throw new IllegalArgumentException("duplicate_state_field");
            }
            String marker = unb64(required(singles, "marker"));
            String encodedObservation = singles.get("markerObservation");
            String observation = encodedObservation == null ? "" : unb64(encodedObservation);
            return BridgeState.restore(unb64(required(singles, "device")), unb64(required(singles, "epoch")),
                    Long.parseLong(required(singles, "sequence")), unb64(required(singles, "cursor")), unb64(required(singles, "serverEpoch")),
                    Integer.parseInt(required(singles, "outboxLimit")), Integer.parseInt(required(singles, "appliedLimit")),
                    events, applied, water, prints, pending, marker.isEmpty() ? null : marker,
                    observation.isEmpty() ? null : observation);
        } catch (RuntimeException error) { throw new CorruptStateException("invalid_state", error); }
    }

    private static String field(String key, String value) { return key + "=" + b64(value); }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String required(Map<String, String> values, String key) { String value = values.get(key); if (value == null) throw new IllegalArgumentException("missing_" + key); return value; }
    private static String join(List<String> lines) { StringBuilder value = new StringBuilder(); for (String line : lines) value.append(line).append('\n'); return value.toString(); }

    private static void writeForced(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }
    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException unavailableOnProvider) {
            // The forced backup generation still guarantees recovery when Android cannot fsync a directory handle.
        }
    }

    private static void pairLong(String value, Map<String, Long> target) {
        int split = value.lastIndexOf(':'); if (split < 1) throw new IllegalArgumentException("water");
        String key = unb64(value.substring(0, split));
        if (target.put(key, Long.valueOf(value.substring(split + 1))) != null) throw new IllegalArgumentException("duplicate_water");
    }
    private static void pairString(String value, Map<String, String> target) {
        int split = value.lastIndexOf(':'); if (split < 1) throw new IllegalArgumentException("print");
        target.put(unb64(value.substring(0, split)), unb64(value.substring(split + 1)));
    }
}
