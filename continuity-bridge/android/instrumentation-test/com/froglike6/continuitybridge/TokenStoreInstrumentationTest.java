package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class TokenStoreInstrumentationTest extends InstrumentationTestCase {
    private static final String ALIAS = "continuity_bridge_token_aes_v1";
    private static final String OWNER_ALIAS = "continuity_bridge_token_test_owner_hmac_v1";
    private static final String ENVELOPE_PREFS = "bridge_secret_envelope";
    private static final String ENVELOPE_KEY = "token_envelope";
    private static final String OWNER_PREFS = "tokenstore_probe_ownership";
    private static final String OWNER_KEY = "marker";
    private static final String PROBE_PREFS = "tokenstore_probe";
    private static final String FIXTURE_MARKER_KEY = "fixture_marker";
    private static final String FIXTURE_ENVELOPE_KEY = "fixture_envelope";
    private static final String FORGED = "v1|forged|owned|invalid|AAAA";
    private static final String MALFORMED = "invalid";
    private static String replacementAfterValidation;

    public void testProductionTokenStorePutThenNewInstanceGet() {
        try {
            String mode = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("mode", "write");
            Context target = getInstrumentation().getTargetContext();
            Context test = getInstrumentation().getContext();
            if ("write".equals(mode)) { writeOwned(target); return; }
            if ("read".equals(mode)) { assertOwned(target); return; }
            if ("assertPresent".equals(mode)) { assertPresent(target, test); return; }
            if ("cleanup".equals(mode)) { cleanupOwned(target); return; }
            if ("raceReplace".equals(mode)) { raceReplace(target); return; }
            if ("interruptMarker".equals(mode)) { claimClean(target, UUID.randomUUID().toString()); return; }
            if ("interruptPending".equals(mode)) { reserveClean(target, UUID.randomUUID().toString()); return; }
            if ("interruptAlias".equals(mode)) { interruptAfterAlias(target); return; }
            if ("recoverPending".equals(mode)) { recoverPending(target); return; }
            if ("seedUnowned".equals(mode)) { seedUnowned(target, test); reportState(target, test); return; }
            if ("assertUnowned".equals(mode)) { assertUnowned(target, test); reportState(target, test); return; }
            if ("restoreUnownedFixture".equals(mode)) { restoreUnownedFixture(target, test); return; }
            if ("clearUnownedFixture".equals(mode)) { clearUnownedFixture(target, test); return; }
            if ("forgeMarker".equals(mode)) { put(targetOwner(target), OWNER_KEY, FORGED); return; }
            if ("malformMarker".equals(mode)) { put(targetOwner(target), OWNER_KEY, MALFORMED); return; }
            if ("removeMarker".equals(mode)) { if (!targetOwner(target).edit().remove(OWNER_KEY).commit()) throw new IllegalStateException(); return; }
            if ("seedUnownedAlias".equals(mode)) { seedUnownedAlias(target); reportState(target, test); return; }
            if ("assertUnownedAlias".equals(mode)) { assertUnownedAlias(target); reportState(target, test); return; }
            if ("clearUnownedAliasFixture".equals(mode)) { clearUnownedAliasFixture(target); return; }
            if ("report".equals(mode)) { reportState(target, test); return; }
            if ("assertClean".equals(mode)) { assertClean(target); return; }
            throw new IllegalArgumentException();
        } catch (Throwable error) { fail(simpleChain(error)); }
    }

    private static void writeOwned(Context target) throws Exception {
        if (targetOwner(target).contains(OWNER_KEY)) cleanupOwned(target);
        String opaque = UUID.randomUUID().toString();
        reserveClean(target, opaque);
        new TokenStore(target).put(opaque);
        recordOwned(target, opaque);
        assertOwned(target);
    }


    private static void claimClean(Context target, String opaque) throws Exception {
        assertClean(target);
        put(targetOwner(target), OWNER_KEY, "v2|" + UUID.randomUUID() + "|claim|" + digest(opaque) + "|-|-");
    }

    private static void reserveClean(Context target, String opaque) throws Exception {
        claimClean(target, opaque);
        createOwnerKey();
        put(targetOwner(target), OWNER_KEY, join(marker("pending", opaque, null)));
    }

    private static void assertOwned(Context target) throws Exception {
        String[] fields = requireOwnedMarker(target);
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        if (stored == null || !keyStore().containsAlias(ALIAS)) throw new SecurityException();
        requireEqual(fields[3], digest(new TokenStore(target).get()));
        requireEqual(fields[4], digest(stored));
    }

    private void assertPresent(Context target, Context test) throws Exception {
        if (envelope(target).getString(ENVELOPE_KEY, null) == null || !keyStore().containsAlias(ALIAS)) throw new SecurityException();
        if (new TokenStore(target).get() == null) throw new SecurityException();
        reportState(target, test);
    }

    private static void cleanupOwned(Context target) throws Exception {
        String marker = targetOwner(target).getString(OWNER_KEY, null);
        if (marker == null) throw new SecurityException();
        String[] fields = parse(marker);
        if ("claim".equals(fields[2])) {
            if (keyStore().containsAlias(ALIAS) || envelope(target).contains(ENVELOPE_KEY)) throw new SecurityException();
            retireOwner(target);
            assertClean(target); return;
        }
        if ("pending".equals(fields[2])) {
            requirePendingMarker(target);
            if (keyStore().containsAlias(ALIAS) || envelope(target).contains(ENVELOPE_KEY)) throw new SecurityException();
            retireOwner(target);
            assertClean(target); return;
        }
        fields = requireOwnedMarker(target);
        SharedPreferences envelope = envelope(target);
        String stored = envelope.getString(ENVELOPE_KEY, null);
        KeyStore keys = keyStore();
        boolean alias = keys.containsAlias(ALIAS);
        if (stored != null) {
            if (!alias) throw new SecurityException();
            requireEqual(fields[3], digest(new TokenStore(target).get()));
            requireEqual(fields[4], digest(stored));
        }
        injectReplacement(target);
        retireOwner(target);
    }

    private static void raceReplace(Context target) throws Exception {
        writeOwned(target);
        assertOwned(target);
        replacementAfterValidation = UUID.randomUUID().toString();
        try {
            cleanupOwned(target);
            requireEqual(digest(replacementAfterValidation), digest(new TokenStore(target).get()));
        } finally { replacementAfterValidation = null; }
    }

    private static String[] requireOwnedMarker(Context target) throws Exception {
        String[] fields = parse(targetOwner(target).getString(OWNER_KEY, null));
        if (!"owned".equals(fields[2]) || !keyStore().containsAlias(OWNER_ALIAS)) throw new SecurityException();
        if (!MessageDigest.isEqual(Base64.decode(fields[5], Base64.NO_WRAP), Base64.decode(ownerMac(payload(fields)), Base64.NO_WRAP))) throw new SecurityException();
        return fields;
    }
    private static String[] requirePendingMarker(Context target) throws Exception {
        String[] fields = parse(targetOwner(target).getString(OWNER_KEY, null));
        if (!"pending".equals(fields[2]) || !keyStore().containsAlias(OWNER_ALIAS)
                || !MessageDigest.isEqual(Base64.decode(fields[5], Base64.NO_WRAP), Base64.decode(ownerMac(payload(fields)), Base64.NO_WRAP))) throw new SecurityException();
        return fields;
    }

    private static String[] parse(String marker) {
        String[] fields = marker.split("\\|", -1);
        if (fields.length != 6 || !"v2".equals(fields[0]) || fields[1].isEmpty() || fields[3].isEmpty()
                || (!("claim".equals(fields[2]) && "-".equals(fields[4]) && "-".equals(fields[5]))
                && !(("pending".equals(fields[2]) || "fixtureAlias".equals(fields[2])) && "-".equals(fields[4]) && !fields[5].isEmpty())
                && !(("owned".equals(fields[2]) || "fixture".equals(fields[2])) && !fields[4].isEmpty() && !fields[5].isEmpty()))) throw new SecurityException();
        return fields;
    }

    private static void interruptAfterAlias(Context target) throws Exception {
        reserveClean(target, UUID.randomUUID().toString());
        createProductionAlias();
    }

    private static void seedUnowned(Context target, Context test) throws Exception {
        assertClean(target);
        String opaque = UUID.randomUUID().toString();
        new TokenStore(target).put(opaque);
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        createOwnerKey();
        String[] fixture = marker("fixture", opaque, stored);
        put(targetOwner(target), OWNER_KEY, join(fixture));
        put(probe(target), FIXTURE_MARKER_KEY, join(fixture));
        put(probe(target), FIXTURE_ENVELOPE_KEY, stored);
    }

    private static void seedUnownedAlias(Context target) throws Exception {
        assertClean(target);
        createProductionAlias();
        createOwnerKey();
        String[] fixture = marker("fixtureAlias", UUID.randomUUID().toString(), null);
        put(targetOwner(target), OWNER_KEY, join(fixture));
        put(probe(target), FIXTURE_MARKER_KEY, join(fixture));
    }

    private static void assertUnownedAlias(Context target) throws Exception {
        if (!keyStore().containsAlias(ALIAS) || envelope(target).contains(ENVELOPE_KEY)) throw new SecurityException();
        requireAliasFixture(target);
    }

    private static void clearUnownedAliasFixture(Context target) throws Exception {
        String backup = probe(target).getString(FIXTURE_MARKER_KEY, null);
        requireEqual(backup, targetOwner(target).getString(OWNER_KEY, null));
        requireAliasFixture(target);
        retireOwner(target);
        if (!probe(target).edit().remove(FIXTURE_MARKER_KEY).commit()) throw new IllegalStateException();
    }

    private static void assertUnowned(Context target, Context test) throws Exception {
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        if (!keyStore().containsAlias(ALIAS)) throw new SecurityException();
        String[] fixture = requireFixtureBackup(target);
        requireEqual(fixture[4], digest(stored));
        requireEqual(fixture[3], digest(new TokenStore(target).get()));
    }

    private static void clearUnownedFixture(Context target, Context test) throws Exception {
        String backup = probe(target).getString(FIXTURE_MARKER_KEY, null);
        requireEqual(backup, targetOwner(target).getString(OWNER_KEY, null));
        String[] fixture = requireFixtureMarker(target);
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        if (stored != null) {
            requireEqual(fixture[4], digest(stored));
            requireEqual(fixture[3], digest(new TokenStore(target).get()));
        }
        retireOwner(target);
        if (!probe(target).edit().remove(FIXTURE_MARKER_KEY).remove(FIXTURE_ENVELOPE_KEY).commit()) throw new IllegalStateException();
    }

    private static void restoreUnownedFixture(Context target, Context test) throws Exception {
        assertUnowned(target, test);
        put(targetOwner(target), OWNER_KEY, probe(target).getString(FIXTURE_MARKER_KEY, null));
        requireFixtureMarker(target);
    }

    private static void recoverPending(Context target) throws Exception {
        String[] pending = requirePendingMarker(target);
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        if (stored == null || !keyStore().containsAlias(ALIAS)) throw new SecurityException();
        String token = new TokenStore(target).get();
        requireEqual(pending[3], digest(token));
        put(targetOwner(target), OWNER_KEY, join(marker("owned", token, stored)));
        assertOwned(target);
    }

    private void reportState(Context target, Context test) throws Exception {
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        Bundle status = new Bundle();
        status.putString("state", "alias=" + keyStore().containsAlias(ALIAS) + ",envelopeChars="
                + (stored == null ? 0 : stored.length()) + ",envelopeSha256=" + digest(stored)
                + ",targetMarker=" + targetOwner(target).contains(OWNER_KEY) + ",ownerAlias=" + keyStore().containsAlias(OWNER_ALIAS)
                + ",fixtureBackup=" + probe(target).contains(FIXTURE_MARKER_KEY));
        getInstrumentation().sendStatus(2, status);
    }

    private static void assertClean(Context target) throws Exception {
        if (keyStore().containsAlias(ALIAS) || keyStore().containsAlias(OWNER_ALIAS)
                || envelope(target).contains(ENVELOPE_KEY) || targetOwner(target).contains(OWNER_KEY)) throw new SecurityException();
    }
    private static SharedPreferences envelope(Context context) { return context.getSharedPreferences(ENVELOPE_PREFS, Context.MODE_PRIVATE); }
    private static SharedPreferences targetOwner(Context context) { return context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE); }
    private static SharedPreferences probe(Context context) { return context.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE); }
    private static void injectReplacement(Context target) throws Exception {
        if (replacementAfterValidation != null) new TokenStore(target).put(replacementAfterValidation);
    }
    private static void retireOwner(Context target) throws Exception {
        if (!targetOwner(target).edit().remove(OWNER_KEY).commit()) throw new IllegalStateException();
        if (keyStore().containsAlias(OWNER_ALIAS)) keyStore().deleteEntry(OWNER_ALIAS);
    }
    private static void put(SharedPreferences prefs, String key, String value) { if (!prefs.edit().putString(key, value).commit()) throw new IllegalStateException(); }
    private static KeyStore keyStore() throws Exception { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store; }
    private static void createOwnerKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(OWNER_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build());
        generator.generateKey();
    }
    private static void createProductionAlias() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        generator.generateKey();
    }
    private static String ownerMac(String payload) throws Exception {
        SecretKey key = (SecretKey) keyStore().getKey(OWNER_ALIAS, null);
        if (key == null) throw new SecurityException();
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key);
        return Base64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    private static String digest(String value) throws Exception {
        if (value == null) return null;
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
    private static String[] marker(String state, String token, String stored) throws Exception {
        String[] fields = new String[] { "v2", UUID.randomUUID().toString(), state, digest(token), stored == null ? "-" : digest(stored), "" };
        fields[5] = ownerMac(payload(fields));
        return fields;
    }
    private static void recordOwned(Context target, String token) throws Exception {
        requirePendingMarker(target);
        String stored = envelope(target).getString(ENVELOPE_KEY, null);
        if (stored == null || !keyStore().containsAlias(ALIAS)) throw new SecurityException();
        put(targetOwner(target), OWNER_KEY, join(marker("owned", token, stored)));
    }
    private static String payload(String[] fields) { return fields[0] + "|" + fields[1] + "|" + fields[2] + "|" + fields[3] + "|" + fields[4]; }
    private static String join(String[] fields) { return payload(fields) + "|" + fields[5]; }
    private static String[] requireFixtureBackup(Context target) throws Exception {
        String[] fields = parse(probe(target).getString(FIXTURE_MARKER_KEY, null));
        if (!"fixture".equals(fields[2]) || !keyStore().containsAlias(OWNER_ALIAS)
                || !MessageDigest.isEqual(Base64.decode(fields[5], Base64.NO_WRAP), Base64.decode(ownerMac(payload(fields)), Base64.NO_WRAP))) throw new SecurityException();
        return fields;
    }
    private static String[] requireFixtureMarker(Context target) throws Exception {
        String[] fields = parse(targetOwner(target).getString(OWNER_KEY, null));
        if (!"fixture".equals(fields[2]) || !keyStore().containsAlias(OWNER_ALIAS)
                || !MessageDigest.isEqual(Base64.decode(fields[5], Base64.NO_WRAP), Base64.decode(ownerMac(payload(fields)), Base64.NO_WRAP))) throw new SecurityException();
        return fields;
    }
    private static String[] requireAliasFixture(Context target) throws Exception {
        String[] fields = parse(targetOwner(target).getString(OWNER_KEY, null));
        if (!"fixtureAlias".equals(fields[2]) || !keyStore().containsAlias(OWNER_ALIAS)
                || !MessageDigest.isEqual(Base64.decode(fields[5], Base64.NO_WRAP), Base64.decode(ownerMac(payload(fields)), Base64.NO_WRAP))) throw new SecurityException();
        return fields;
    }
    private static void requireEqual(String expected, String actual) { if (expected == null || !expected.equals(actual)) throw new AssertionError(); }
    private static String simpleChain(Throwable error) {
        StringBuilder chain = new StringBuilder(); Throwable current = error;
        while (current != null) { if (chain.length() > 0) chain.append("->"); chain.append(current.getClass().getSimpleName()); current = current.getCause(); }
        return chain.toString();
    }
}
