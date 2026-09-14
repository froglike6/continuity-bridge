package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class SettingsSaveInstrumentationTest extends InstrumentationTestCase {
    public void testAccessPairPersistsEncryptedAndReuseRequiresTheSameClientId() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        SharedPreferences live = target.getSharedPreferences("bridge_secret_envelope", Context.MODE_PRIVATE);
        Map<String, ?> before = live.getAll();
        requireExistingKey();
        CommitPreferences preferences = new CommitPreferences(true);
        Context isolated = isolatedContext(target, preferences);
        AccessCredentials pair = new AccessCredentials("synthetic-device.access", "cfast_synthetic_private_123");
        new TokenStore(isolated).putAccess(pair);
        String envelope = preferences.getString(TokenStore.ACCESS_ENVELOPE, "");
        String bytes = new String(java.util.Base64.getDecoder().decode(envelope), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("Only the encrypted pair is persisted.", preferences.getAll().size() == 1
                && !envelope.contains(pair.clientId()) && !envelope.contains(pair.clientSecret())
                && !bytes.contains(pair.clientId()) && !bytes.contains(pair.clientSecret()));
        AccessCredentials restored = new TokenStore(isolated).loadAccess();
        assertTrue("A recreated store restores the complete pair.", pair.clientId().equals(restored.clientId())
                && pair.clientSecret().equals(restored.clientSecret()));
        assertTrue("An empty secret keeps the pair only for the same ID.",
                AccessCredentials.forSave(restored.clientId(), "", restored) == restored);
        try {
            AccessCredentials.forSave("changed-device.access", "", restored);
            fail("A changed ID must require a new secret.");
        } catch (AccessCredentials.ValidationException expected) {
            assertTrue("Recovery belongs to the secret field.", expected.field() == AccessCredentials.Field.CLIENT_SECRET);
        }
        AccessCredentials replacement = AccessCredentials.forSave("changed-device.access", "new-secret", restored);
        new TokenStore(isolated).putAccess(replacement);
        assertTrue("Replacing credentials saves the new pair together.",
                replacement.clientId().equals(new TokenStore(isolated).loadAccess().clientId())
                        && replacement.clientSecret().equals(new TokenStore(isolated).loadAccess().clientSecret()));
        assertTrue("Live secret settings are unchanged.", before.equals(live.getAll()));
    }

    public void testAccessCommitFailureAndCorruptEnvelopeFailClosed() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        SharedPreferences live = target.getSharedPreferences("bridge_secret_envelope", Context.MODE_PRIVATE);
        Map<String, ?> before = live.getAll();
        requireExistingKey();
        CommitPreferences failed = new CommitPreferences(false);
        try {
            new TokenStore(isolatedContext(target, failed)).putAccess(new AccessCredentials("device.access", "cfast_synthetic"));
            fail("Failed Access persistence must reach the caller.");
        } catch (IOException expected) {
            assertEquals("secure_access_persistence_failed", expected.getMessage());
        }
        assertTrue("Failed persistence must not save a partial pair.", failed.getAll().isEmpty() && failed.commitCalls == 1);
        CommitPreferences corrupt = new CommitPreferences(true);
        corrupt.edit().putString(TokenStore.ACCESS_ENVELOPE, "corrupt-envelope").commit();
        TokenStore store = new TokenStore(isolatedContext(target, corrupt));
        try { store.loadAccess(); fail("Corrupt Access envelope must not become a missing credential."); }
        catch (SecureStoreException expected) { assertEquals("secure_access_unavailable", expected.getMessage()); }
        assertNull("Disabled Access bypasses even a corrupt envelope.", AccessCredentials.required(false, store));
        assertTrue("Live secret settings are unchanged.", before.equals(live.getAll()));
    }

    public void testAccessTogglePersistsWithoutPlaintextCredentials() {
        Context target = getInstrumentation().getTargetContext();
        SharedPreferences live = target.getSharedPreferences(ConfigStore.PREFERENCES, Context.MODE_PRIVATE);
        Map<String, ?> before = live.getAll();
        CommitPreferences preferences = new CommitPreferences(true);
        Context isolated = isolatedContext(target, preferences);
        ConfigStore store = new ConfigStore(isolated);
        assertFalse("Access is opt in.", store.accessEnabled());
        store.save("https://example.test", "", true, true);
        assertTrue("Access enable survives a new config instance.", new ConfigStore(isolated).accessEnabled());
        assertTrue("Ordinary settings contain only address, TLS and the enabled flag.", preferences.getAll().size() == 4);
        store.save("https://example.test", "", true, false);
        assertFalse("Access disable survives a new config instance.", new ConfigStore(isolated).accessEnabled());
        assertTrue("Live configuration is unchanged.", before.equals(live.getAll()));
    }

    private static void requireExistingKey() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        assertTrue("Provision a token before this isolated test; the test must not create a production key.",
                keys.containsAlias("continuity_bridge_token_aes_v1"));
    }

    public void testCommitResultsReachCallerWithoutChangingLiveSettings() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        SharedPreferences configuration = target.getSharedPreferences(ConfigStore.PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences secret = target.getSharedPreferences("bridge_secret_envelope", Context.MODE_PRIVATE);
        Map<String, ?> configurationBefore = configuration.getAll();
        Map<String, ?> secretBefore = secret.getAll();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        assertTrue("Provision a token before this read-only test; the test must not create a production key.",
                keys.containsAlias("continuity_bridge_token_aes_v1"));

        for (boolean commitResult : new boolean[] { true, false }) {
            CommitPreferences preferences = new CommitPreferences(commitResult);
            ConfigStore store = new ConfigStore(isolatedContext(target, preferences));
            boolean failed = false;
            try { store.save("https://example.test", "", true); }
            catch (IllegalStateException error) {
                failed = true;
                assertEquals("configuration_persistence_failed", error.getMessage());
            }
            assertEquals("ConfigStore must report the commit result.", !commitResult, failed);
            assertEquals("ConfigStore must commit synchronously exactly once.", 1, preferences.commitCalls);
            assertEquals("ConfigStore must persist the validated endpoint on success.",
                    commitResult ? "https://example.test" : null, preferences.getString("endpoint", null));
        }

        for (boolean commitResult : new boolean[] { true, false }) {
            CommitPreferences preferences = new CommitPreferences(commitResult);
            TokenStore store = new TokenStore(isolatedContext(target, preferences));
            boolean failed = false;
            try { store.put("synthetic-commit-boundary-probe"); }
            catch (IOException error) {
                failed = true;
                assertEquals("secure_token_persistence_failed", error.getMessage());
            }
            assertEquals("TokenStore must report the commit result.", !commitResult, failed);
            assertEquals("TokenStore must commit synchronously exactly once.", 1, preferences.commitCalls);
            assertEquals("Only a successful commit stores the synthetic envelope.", commitResult,
                    preferences.contains("token_envelope"));
            if (commitResult) {
                assertTrue("The isolated envelope must decrypt through production TokenStore.",
                        "synthetic-commit-boundary-probe".equals(store.get()));
            }
        }
        assertTrue("Live configuration must remain unchanged.", configurationBefore.equals(configuration.getAll()));
        assertTrue("Live token envelope must remain unchanged.", secretBefore.equals(secret.getAll()));
    }

    private static Context isolatedContext(Context target, final SharedPreferences preferences) {
        return new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
    }

    private static final class CommitPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<String, Object>();
        private final boolean commitResult;
        private int commitCalls;

        CommitPreferences(boolean commitResult) { this.commitResult = commitResult; }

        @Override public Map<String, ?> getAll() { return new HashMap<String, Object>(values); }
        @Override public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { throw new UnsupportedOperationException(); }
        @Override public int getInt(String key, int fallback) { throw new UnsupportedOperationException(); }
        @Override public long getLong(String key, long fallback) { throw new UnsupportedOperationException(); }
        @Override public float getFloat(String key, float fallback) { throw new UnsupportedOperationException(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new UnsupportedOperationException(); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new UnsupportedOperationException(); }

        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<String, Object>();
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                @Override public boolean commit() {
                    commitCalls++;
                    if (commitResult) values.putAll(pending);
                    return commitResult;
                }
                @Override public void apply() { throw new AssertionError("Save must wait for the commit result."); }
                @Override public Editor putStringSet(String key, Set<String> value) { throw new UnsupportedOperationException(); }
                @Override public Editor putInt(String key, int value) { throw new UnsupportedOperationException(); }
                @Override public Editor putLong(String key, long value) { throw new UnsupportedOperationException(); }
                @Override public Editor putFloat(String key, float value) { throw new UnsupportedOperationException(); }
                @Override public Editor remove(String key) { throw new UnsupportedOperationException(); }
                @Override public Editor clear() { throw new UnsupportedOperationException(); }
            };
        }
    }
}
