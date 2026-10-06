package com.deenwallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeenWallet biometric login.
 *
 * - One credential PER ACCOUNT. A phone can hold several, and the app always
 *   says which account it is signing in to, so one account's fingerprint
 *   login can never open another account.
 * - The private key lives in the Android Keystore and never leaves it.
 * - When the phone has strong (class 3) biometrics, the key is locked to the
 *   fingerprint: it cannot sign without a fresh scan, and it stops working if
 *   someone adds a new fingerprint to the phone. Phones with only weak
 *   biometrics still work exactly as before (set PREFER_STRONG to false to
 *   switch the stronger mode off everywhere).
 * - Nothing is saved until the fingerprint has really succeeded.
 * - prepare() creates the key ahead of time, so the prompt appears at once.
 */
@CapacitorPlugin(name = "DeenWalletBiometric")
public class DeenWalletBiometricPlugin extends Plugin {

    // Stronger mode where the phone supports it. Flip to false to use the old
    // behaviour (weak biometrics, key not locked to the fingerprint).
    private static final boolean PREFER_STRONG = true;

    private static final String PREFS = "deenwallet_biometric";
    private static final String KEY_ACCOUNTS = "accounts";
    // Older app versions stored a single credential under these keys.
    private static final String LEGACY_CREDENTIAL_ID = "credential_id";
    private static final String LEGACY_KEY_ALIAS = "key_alias";
    private static final String ALIAS_PREFIX = "deenwallet_bio_";

    private final AtomicBoolean promptActive = new AtomicBoolean(false);

    // A key made in advance by prepare(), waiting to be used by register().
    private String pendingAlias = null;
    private boolean pendingStrong = false;

    // ------------------------------------------------------------------
    // Availability
    // ------------------------------------------------------------------

    @PluginMethod
    public void isAvailable(PluginCall call) {
        BiometricManager manager = BiometricManager.from(getContext());

        int weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        int strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        JSObject out = new JSObject();
        out.put("available", weak == BiometricManager.BIOMETRIC_SUCCESS);
        out.put("reason", weak);
        out.put("strong", strong == BiometricManager.BIOMETRIC_SUCCESS);
        out.put("authenticator", strong == BiometricManager.BIOMETRIC_SUCCESS ? "BIOMETRIC_STRONG" : "BIOMETRIC_WEAK");
        call.resolve(out);
    }

    // ------------------------------------------------------------------
    // What is stored on this phone
    // ------------------------------------------------------------------

    /** Every account that has biometric login on this phone. */
    @PluginMethod
    public void listCredentials(PluginCall call) {
        JSArray list = new JSArray();
        try {
            JSONArray accounts = loadAccounts();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                JSObject o = new JSObject();
                o.put("accountId", a.optString("accountId", ""));
                o.put("credentialId", a.optString("credentialId", ""));
                o.put("firstName", a.optString("firstName", ""));
                o.put("maskedEmail", a.optString("maskedEmail", ""));
                o.put("accountNumber", a.optString("accountNumber", ""));
                o.put("strong", a.optBoolean("strong", false));
                list.put(o);
            }
        } catch (Exception ignored) {
        }
        JSObject out = new JSObject();
        out.put("credentials", list);
        call.resolve(out);
    }

    /** Kept for older web code: reports the first credential, if any. */
    @PluginMethod
    public void hasCredential(PluginCall call) {
        JSObject out = new JSObject();
        try {
            JSONArray accounts = loadAccounts();
            if (accounts.length() > 0) {
                out.put("registered", true);
                out.put("credentialId", accounts.getJSONObject(0).optString("credentialId", ""));
            } else {
                out.put("registered", false);
            }
        } catch (Exception e) {
            out.put("registered", false);
        }
        call.resolve(out);
    }

    // ------------------------------------------------------------------
    // Get ready ahead of time
    // ------------------------------------------------------------------

    /**
     * Creates the key now, so tapping "Enable" shows the fingerprint prompt
     * straight away instead of waiting for the key to be made.
     */
    @PluginMethod
    public void prepare(PluginCall call) {
        try {
            removeOrphanKeys();
            if (pendingAlias == null || !keyExists(pendingAlias)) {
                boolean strong = canUseStrong();
                String alias = ALIAS_PREFIX + UUID.randomUUID().toString().replace("-", "");
                generateKey(alias, strong);
                pendingAlias = alias;
                pendingStrong = strong;
            }
        } catch (Exception ignored) {
            // Optional speed-up: register() makes the key itself if this failed.
            pendingAlias = null;
        }
        call.resolve();
    }

    // ------------------------------------------------------------------
    // Register (turn biometric login on for one account)
    // ------------------------------------------------------------------

    @PluginMethod
    public void register(PluginCall call) {
        String challenge = call.getString("challenge");
        if (challenge == null || challenge.isEmpty()) {
            call.reject("Challenge is required");
            return;
        }
        if (!promptActive.compareAndSet(false, true)) {
            call.reject("Another fingerprint request is already open", "BUSY");
            return;
        }

        final String accountId = nz(call.getString("accountId"));
        final String firstName = nz(call.getString("firstName"));
        final String maskedEmail = nz(call.getString("maskedEmail"));
        final String accountNumber = nz(call.getString("accountNumber"));

        String alias;
        boolean strong;
        try {
            if (pendingAlias != null && keyExists(pendingAlias)) {
                alias = pendingAlias;
                strong = pendingStrong;
            } else {
                strong = canUseStrong();
                alias = ALIAS_PREFIX + UUID.randomUUID().toString().replace("-", "");
                generateKey(alias, strong);
            }
            pendingAlias = alias;          // reused if the person cancels and taps again
            pendingStrong = strong;
        } catch (Exception e) {
            promptActive.set(false);
            call.reject("Could not prepare biometric login: " + e.getMessage());
            return;
        }

        final String keyAlias = alias;
        final boolean keyIsStrong = strong;
        final String credentialId = UUID.randomUUID().toString();

        showPrompt(call, keyAlias, keyIsStrong, challenge, true, new SignedResult() {
            @Override
            public void onSigned(String signatureB64, String publicKeyB64) {
                try {
                    // Only now, after a real fingerprint, is anything saved.
                    JSONArray accounts = loadAccounts();
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < accounts.length(); i++) {
                        JSONObject a = accounts.getJSONObject(i);
                        boolean sameAccount = !accountId.isEmpty() && accountId.equals(a.optString("accountId", ""));
                        if (sameAccount) {
                            deleteKey(a.optString("alias", ""));   // replaced by the new one
                        } else {
                            kept.put(a);
                        }
                    }
                    JSONObject entry = new JSONObject();
                    entry.put("accountId", accountId);
                    entry.put("credentialId", credentialId);
                    entry.put("alias", keyAlias);
                    entry.put("firstName", firstName);
                    entry.put("maskedEmail", maskedEmail);
                    entry.put("accountNumber", accountNumber);
                    entry.put("strong", keyIsStrong);
                    kept.put(entry);
                    saveAccounts(kept);
                    if (keyAlias.equals(pendingAlias)) pendingAlias = null;

                    JSObject out = new JSObject();
                    out.put("credentialId", credentialId);
                    out.put("signature", signatureB64);
                    out.put("publicKey", publicKeyB64);
                    call.resolve(out);
                } catch (Exception e) {
                    call.reject("Could not save biometric login: " + e.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Authenticate (sign in to one account)
    // ------------------------------------------------------------------

    @PluginMethod
    public void authenticate(PluginCall call) {
        String challenge = call.getString("challenge");
        if (challenge == null || challenge.isEmpty()) {
            call.reject("Challenge is required");
            return;
        }
        String credentialId = nz(call.getString("credentialId"));
        String accountId = nz(call.getString("accountId"));

        JSONObject entry = findEntry(credentialId, accountId);
        if (entry == null) {
            call.reject("Biometric login is not set up for this account on this phone", "NOT_SET_UP");
            return;
        }
        if (!promptActive.compareAndSet(false, true)) {
            call.reject("Another fingerprint request is already open", "BUSY");
            return;
        }

        final String alias = entry.optString("alias", "");
        final boolean strong = entry.optBoolean("strong", false);
        final String foundCredentialId = entry.optString("credentialId", "");

        if (!keyExists(alias)) {
            removeEntry(foundCredentialId);
            promptActive.set(false);
            call.reject("Biometric login needs to be set up again on this phone.", "NOT_SET_UP");
            return;
        }

        showPrompt(call, alias, strong, challenge, false, new SignedResult() {
            @Override
            public void onSigned(String signatureB64, String publicKeyB64) {
                JSObject out = new JSObject();
                out.put("credentialId", foundCredentialId);
                out.put("signature", signatureB64);
                call.resolve(out);
            }
        });
    }

    // ------------------------------------------------------------------
    // Remove
    // ------------------------------------------------------------------

    /**
     * Removes one credential (by credentialId or accountId). With no
     * arguments it removes all of them, like older app versions did.
     */
    @PluginMethod
    public void clearCredential(PluginCall call) {
        try {
            String credentialId = nz(call.getString("credentialId"));
            String accountId = nz(call.getString("accountId"));
            JSONArray accounts = loadAccounts();
            JSONArray kept = new JSONArray();
            boolean removeAll = credentialId.isEmpty() && accountId.isEmpty();

            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                boolean match = removeAll
                        || (!credentialId.isEmpty() && credentialId.equals(a.optString("credentialId", "")))
                        || (!accountId.isEmpty() && accountId.equals(a.optString("accountId", "")));
                if (match) deleteKey(a.optString("alias", ""));
                else kept.put(a);
            }
            saveAccounts(kept);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not remove biometric credential");
        }
    }

    // ------------------------------------------------------------------
    // The fingerprint prompt
    // ------------------------------------------------------------------

    private interface SignedResult {
        void onSigned(String signatureB64, String publicKeyB64);
    }

    private void showPrompt(final PluginCall call,
                            final String alias,
                            final boolean strong,
                            final String challenge,
                            final boolean registration,
                            final SignedResult done) {
        final Signature signature;
        final PrivateKey privateKey;
        try {
            KeyStore keyStore = getKeyStore();
            privateKey = (PrivateKey) keyStore.getKey(alias, null);
            if (privateKey == null) {
                promptActive.set(false);
                call.reject("Biometric credential is unavailable. Set it up again.", "NOT_SET_UP");
                return;
            }
            signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);   // for a locked key this does not need the fingerprint yet
        } catch (KeyPermanentlyInvalidatedException e) {
            // A new fingerprint was added to the phone, so the old key stopped working on purpose.
            handleInvalidated(call, alias);
            return;
        } catch (Exception e) {
            promptActive.set(false);
            call.reject("Could not start biometric authentication");
            return;
        }

        final BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                try {
                    Signature signer = signature;
                    if (strong && result.getCryptoObject() != null && result.getCryptoObject().getSignature() != null) {
                        signer = result.getCryptoObject().getSignature();
                    }
                    signer.update(challenge.getBytes(StandardCharsets.UTF_8));
                    byte[] signed = signer.sign();

                    String publicKey = null;
                    if (registration) {
                        publicKey = b64(getKeyStore().getCertificate(alias).getPublicKey().getEncoded());
                    }
                    promptActive.set(false);
                    done.onSigned(b64(signed), publicKey);
                } catch (KeyPermanentlyInvalidatedException e) {
                    handleInvalidated(call, alias);
                } catch (Exception e) {
                    promptActive.set(false);
                    call.reject("Biometric signing failed");
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                promptActive.set(false);
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                        || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        || errorCode == BiometricPrompt.ERROR_CANCELED) {
                    call.reject("Cancelled", "CANCELED");
                } else if (errorCode == BiometricPrompt.ERROR_LOCKOUT
                        || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    call.reject("Too many fingerprint attempts. Try again later or use your password.", "LOCKOUT");
                } else {
                    call.reject("Biometric authentication failed: " + errString);
                }
            }
        };

        final BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(registration ? "Enable DeenWallet biometric login" : "Biometric login")
                .setSubtitle(registration ? "Confirm your identity to enable biometric login" : "Confirm your identity to sign in")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(strong
                        ? BiometricManager.Authenticators.BIOMETRIC_STRONG
                        : BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();

        // The prompt must be created and shown on the main thread.
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    BiometricPrompt prompt = new BiometricPrompt(
                            getActivity(), ContextCompat.getMainExecutor(getContext()), callback);
                    if (strong) {
                        prompt.authenticate(info, new BiometricPrompt.CryptoObject(signature));
                    } else {
                        prompt.authenticate(info);
                    }
                } catch (Exception e) {
                    promptActive.set(false);
                    call.reject("Could not start biometric authentication");
                }
            }
        });
    }

    private void handleInvalidated(PluginCall call, String alias) {
        promptActive.set(false);
        deleteKey(alias);
        try {
            JSONArray accounts = loadAccounts();
            JSONArray kept = new JSONArray();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                if (!alias.equals(a.optString("alias", ""))) kept.put(a);
            }
            saveAccounts(kept);
        } catch (Exception ignored) {
        }
        if (alias.equals(pendingAlias)) pendingAlias = null;
        call.reject("The fingerprints on this phone changed. Log in with your password and turn biometric login on again.", "KEY_INVALIDATED");
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    private boolean canUseStrong() {
        if (!PREFER_STRONG) return false;
        BiometricManager manager = BiometricManager.from(getContext());
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void generateKey(String alias, boolean strong) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256);

        if (strong) {
            // Locked to the fingerprint: needs a fresh scan for every signature,
            // and is invalidated if a new fingerprint is enrolled on the phone.
            builder.setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(-1);
            }
            if (Build.VERSION.SDK_INT >= 24) {
                builder.setInvalidatedByBiometricEnrollment(true);
            }
        }
        generator.initialize(builder.build());
        generator.generateKeyPair();
    }

    private boolean keyExists(String alias) {
        try {
            return alias != null && !alias.isEmpty() && getKeyStore().containsAlias(alias);
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteKey(String alias) {
        try {
            if (alias != null && !alias.isEmpty()) {
                KeyStore ks = getKeyStore();
                if (ks.containsAlias(alias)) ks.deleteEntry(alias);
            }
        } catch (Exception ignored) {
        }
    }

    /** Keys left behind by cancelled setups are removed. */
    private void removeOrphanKeys() {
        try {
            JSONArray accounts = loadAccounts();
            KeyStore ks = getKeyStore();
            Enumeration<String> aliases = ks.aliases();
            java.util.List<String> toDelete = new java.util.ArrayList<>();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!alias.startsWith(ALIAS_PREFIX)) continue;
                if (alias.equals(pendingAlias)) continue;
                boolean used = false;
                for (int i = 0; i < accounts.length(); i++) {
                    if (alias.equals(accounts.getJSONObject(i).optString("alias", ""))) { used = true; break; }
                }
                if (!used) toDelete.add(alias);
            }
            for (String alias : toDelete) ks.deleteEntry(alias);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Stored accounts
    // ------------------------------------------------------------------

    private JSONObject findEntry(String credentialId, String accountId) {
        try {
            JSONArray accounts = loadAccounts();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                if (!credentialId.isEmpty() && credentialId.equals(a.optString("credentialId", ""))) {
                    // When the web page also names an account, they have to agree.
                    if (!accountId.isEmpty() && !a.optString("accountId", "").isEmpty()
                            && !accountId.equals(a.optString("accountId", ""))) {
                        return null;
                    }
                    return a;
                }
            }
            if (credentialId.isEmpty() && !accountId.isEmpty()) {
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject a = accounts.getJSONObject(i);
                    if (accountId.equals(a.optString("accountId", ""))) return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void removeEntry(String credentialId) {
        try {
            JSONArray accounts = loadAccounts();
            JSONArray kept = new JSONArray();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                if (!credentialId.equals(a.optString("credentialId", ""))) kept.put(a);
            }
            saveAccounts(kept);
        } catch (Exception ignored) {
        }
    }

    /** Reads the list, upgrading the old single-credential format the first time. */
    private JSONArray loadAccounts() {
        SharedPreferences p = prefs();
        JSONArray accounts;
        try {
            String raw = p.getString(KEY_ACCOUNTS, null);
            accounts = raw == null ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            accounts = new JSONArray();
        }

        String legacyId = p.getString(LEGACY_CREDENTIAL_ID, null);
        String legacyAlias = p.getString(LEGACY_KEY_ALIAS, null);
        if (legacyId != null && legacyAlias != null) {
            try {
                if (accounts.length() == 0 && keyExists(legacyAlias)) {
                    // Old setups do not know which account they belong to. They are listed
                    // without an account, and the login screen asks to set them up again.
                    JSONObject entry = new JSONObject();
                    entry.put("accountId", "");
                    entry.put("credentialId", legacyId);
                    entry.put("alias", legacyAlias);
                    entry.put("firstName", "");
                    entry.put("maskedEmail", "");
                    entry.put("accountNumber", "");
                    entry.put("strong", false);
                    accounts.put(entry);
                }
            } catch (Exception ignored) {
            }
            p.edit().remove(LEGACY_CREDENTIAL_ID).remove(LEGACY_KEY_ALIAS)
                    .putString(KEY_ACCOUNTS, accounts.toString()).apply();
        }

        // Drop entries whose key no longer exists in the Keystore.
        JSONArray valid = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < accounts.length(); i++) {
            try {
                JSONObject a = accounts.getJSONObject(i);
                if (keyExists(a.optString("alias", ""))) valid.put(a);
                else changed = true;
            } catch (Exception ignored) {
                changed = true;
            }
        }
        if (changed) saveAccounts(valid);
        return valid;
    }

    private void saveAccounts(JSONArray accounts) {
        prefs().edit().putString(KEY_ACCOUNTS, accounts.toString()).apply();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private KeyStore getKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore;
    }
}
