package com.froglike6.continuitybridge;

public final class AccessCredentialSuite {
    private static int cases;
    private AccessCredentialSuite() { }

    static int run() throws Exception {
        for (String secret : new String[] { "cfast_Safe.new-token_123+/=", "abcdef0123456789", "opaque!#$%&'*+-.^_`|~" }) {
            AccessCredentials supplied = new AccessCredentials("device.access", secret);
            AccessCredentials restored = AccessCredentials.decode(supplied.encode());
            check("device.access".equals(restored.clientId()) && secret.equals(restored.clientSecret()), "safe credentials round trip");
        }
        for (String invalid : new String[] { "", "\n", "good\r\nInjected: value", "bad\tvalue", "bad\u0000value", "bad\u007fvalue", "bad\u0085value" }) {
            invalid(invalid, "valid", AccessCredentials.Field.CLIENT_ID);
            invalid("valid", invalid, AccessCredentials.Field.CLIENT_SECRET);
        }
        AccessCredentials saved = new AccessCredentials("saved.access", "cfast_saved");
        check(AccessCredentials.forSave("saved.access", "", saved) == saved, "unchanged ID reuses saved secret");
        missing("changed.access", saved);
        missing("saved.access", null);
        check("cfast_new".equals(AccessCredentials.forSave("changed.access", "cfast_new", saved).clientSecret()), "changed ID with new secret replaces pair");
        check(AccessCredentials.required(false, new AccessCredentials.Provider() {
            @Override public AccessCredentials loadAccess() { throw new AssertionError("disabled Access read secure store"); }
        }) == null, "disabled Access bypasses secure store");
        try {
            AccessCredentials.required(true, new AccessCredentials.Provider() {
                @Override public AccessCredentials loadAccess() { return null; }
            });
            throw new AssertionError("missing enabled credentials accepted");
        } catch (AccessAuthenticationException expected) { cases++; }
        System.out.println("ACCESS_CREDENTIAL_OK cases=" + cases);
        return cases;
    }

    private static void invalid(String id, String secret, AccessCredentials.Field field) {
        try { new AccessCredentials(id, secret); throw new AssertionError("unsafe credential accepted"); }
        catch (AccessCredentials.ValidationException expected) { check(expected.field() == field, "invalid field identified"); }
    }

    private static void missing(String id, AccessCredentials saved) {
        try { AccessCredentials.forSave(id, "", saved); throw new AssertionError("missing secret accepted"); }
        catch (AccessCredentials.ValidationException expected) { check(expected.field() == AccessCredentials.Field.CLIENT_SECRET, "new secret required"); }
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        cases++;
    }
}
