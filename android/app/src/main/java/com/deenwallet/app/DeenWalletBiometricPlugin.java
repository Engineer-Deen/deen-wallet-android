package com.deenwallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

@CapacitorPlugin(name = "DeenWalletBiometric")
public class DeenWalletBiometricPlugin extends Plugin {

    private static final String PREFS = "deenwallet_biometric";
    private static final String KEY_CREDENTIAL_ID = "credential_id";
    private static final String KEY_ALIAS = "key_alias";

    /**
     * Check whether this Android device has any enrolled biometric
     * that Android allows applications to use at the WEAK level.
     *
     * This is intentionally BIOMETRIC_WEAK instead of BIOMETRIC_STRONG
     * so more Android devices can use DeenWallet biometric login.
     */
    @PluginMethod
    public void isAvailable(PluginCall call) {

        BiometricManager manager = BiometricManager.from(getContext());

        int result = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        );

        JSObject out = new JSObject();

        out.put(
                "available",
                result == BiometricManager.BIOMETRIC_SUCCESS
        );

        out.put("reason", result);

        out.put(
                "authenticator",
                "BIOMETRIC_WEAK"
        );

        call.resolve(out);
    }

    /**
     * Check whether this device already has a DeenWallet
     * biometric credential.
     */
    @PluginMethod
    public void hasCredential(PluginCall call) {

        SharedPreferences p = prefs();

        String credentialId =
                p.getString(KEY_CREDENTIAL_ID, null);

        String alias =
                p.getString(KEY_ALIAS, null);

        boolean exists = false;

        try {
            if (credentialId != null && alias != null) {

                KeyStore keyStore = getKeyStore();

                exists = keyStore.containsAlias(alias);
            }

        } catch (Exception ignored) {
        }

        JSObject out = new JSObject();

        out.put(
                "registered",
                exists && credentialId != null
        );

        if (credentialId != null) {
            out.put(
                    "credentialId",
                    credentialId
            );
        }

        call.resolve(out);
    }

    /**
     * Create the device credential.
     *
     * IMPORTANT:
     * The private key is kept inside Android Keystore.
     *
     * We intentionally do NOT call:
     *
     * setUserAuthenticationRequired(true)
     *
     * because Android Keystore cannot bind that key to
     * BIOMETRIC_WEAK.
     *
     * Instead, the biometric prompt is performed before
     * the key is used.
     */
    @PluginMethod
    public void register(PluginCall call) {

        String challenge =
                call.getString("challenge");

        if (challenge == null || challenge.isBlank()) {

            call.reject("Challenge is required");

            return;
        }

        try {

            /*
             * Remove an existing credential first.
             */
            String oldAlias =
                    prefs().getString(KEY_ALIAS, null);

            if (oldAlias != null) {

                try {

                    KeyStore oldStore =
                            getKeyStore();

                    if (oldStore.containsAlias(oldAlias)) {

                        oldStore.deleteEntry(oldAlias);
                    }

                } catch (Exception ignored) {
                }
            }

            /*
             * Create a new credential ID.
             */
            String credentialId =
                    UUID.randomUUID().toString();

            String alias =
                    "deenwallet_bio_" +
                    credentialId.replace("-", "");

            /*
             * Generate an EC key pair inside Android Keystore.
             */
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_EC,
                            "AndroidKeyStore"
                    );

            KeyGenParameterSpec.Builder builder =
                    new KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_SIGN
                    )
                            .setAlgorithmParameterSpec(
                                    new ECGenParameterSpec(
                                            "secp256r1"
                                    )
                            )
                            .setDigests(
                                    KeyProperties.DIGEST_SHA256
                            );

            /*
             * DO NOT add:
             *
             * .setUserAuthenticationRequired(true)
             *
             * because that would require a compatible
             * Keystore authenticator and would prevent
             * weaker biometric implementations from working.
             */

            generator.initialize(
                    builder.build()
            );

            generator.generateKeyPair();

            /*
             * Store only the credential ID and alias.
             *
             * The private key never leaves Android Keystore.
             */
            prefs()
                    .edit()
                    .putString(
                            KEY_CREDENTIAL_ID,
                            credentialId
                    )
                    .putString(
                            KEY_ALIAS,
                            alias
                    )
                    .apply();

            /*
             * Ask the user for biometric authentication.
             */
            signWithBiometric(
                    call,
                    challenge,
                    credentialId,
                    alias,
                    true
            );

        } catch (Exception e) {

            call.reject(
                    "Could not prepare biometric login: "
                            + e.getMessage()
            );
        }
    }

    /**
     * Authenticate an already registered biometric credential.
     */
    @PluginMethod
    public void authenticate(PluginCall call) {

        String challenge =
                call.getString("challenge");

        if (challenge == null || challenge.isBlank()) {

            call.reject("Challenge is required");

            return;
        }

        String credentialId =
                prefs().getString(
                        KEY_CREDENTIAL_ID,
                        null
                );

        String alias =
                prefs().getString(
                        KEY_ALIAS,
                        null
                );

        if (credentialId == null || alias == null) {

            call.reject(
                    "No biometric credential is registered on this device"
            );

            return;
        }

        signWithBiometric(
                call,
                challenge,
                credentialId,
                alias,
                false
        );
    }

    /**
     * Remove the biometric credential from this device.
     */
    @PluginMethod
    public void clearCredential(PluginCall call) {

        try {

            String alias =
                    prefs().getString(
                            KEY_ALIAS,
                            null
                    );

            KeyStore keyStore =
                    getKeyStore();

            if (
                    alias != null &&
                    keyStore.containsAlias(alias)
            ) {

                keyStore.deleteEntry(alias);
            }

        } catch (Exception e) {

            call.reject(
                    "Could not remove biometric credential"
            );

            return;
        }

        prefs()
                .edit()
                .clear()
                .apply();

        call.resolve();
    }

    /**
     * Display Android's biometric prompt.
     *
     * BIOMETRIC_WEAK is deliberately used here so
     * devices with weaker Android-classified biometric
     * implementations can also authenticate.
     */
    private void signWithBiometric(
            PluginCall call,
            String challenge,
            String credentialId,
            String alias,
            boolean registration
    ) {

        try {

            KeyStore keyStore =
                    getKeyStore();

            KeyStore.PrivateKeyEntry entry =
                    (KeyStore.PrivateKeyEntry)
                            keyStore.getEntry(
                                    alias,
                                    null
                            );

            if (entry == null) {

                call.reject(
                        "Biometric credential is unavailable. Register it again."
                );

                return;
            }

            Signature signature =
                    Signature.getInstance(
                            "SHA256withECDSA"
                    );

            BiometricPrompt.AuthenticationCallback callback =
                    new BiometricPrompt.AuthenticationCallback() {

                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult result
                        ) {

                            try {

                                /*
                                 * The biometric prompt has succeeded.
                                 *
                                 * Now use the non-exportable private
                                 * key stored in Android Keystore to sign
                                 * the backend challenge.
                                 */
                                signature.initSign(
                                        entry.getPrivateKey()
                                );

                                signature.update(
                                        challenge.getBytes(
                                                StandardCharsets.UTF_8
                                        )
                                );

                                byte[] signed =
                                        signature.sign();

                                JSObject out =
                                        new JSObject();

                                out.put(
                                        "credentialId",
                                        credentialId
                                );

                                out.put(
                                        "signature",
                                        Base64.encodeToString(
                                                signed,
                                                Base64.URL_SAFE
                                                        | Base64.NO_WRAP
                                                        | Base64.NO_PADDING
                                        )
                                );

                                /*
                                 * During registration the backend
                                 * also needs the public key.
                                 */
                                if (registration) {

                                    out.put(
                                            "publicKey",
                                            Base64.encodeToString(
                                                    entry
                                                            .getCertificate()
                                                            .getPublicKey()
                                                            .getEncoded(),
                                                    Base64.URL_SAFE
                                                            | Base64.NO_WRAP
                                                            | Base64.NO_PADDING
                                            )
                                    );
                                }

                                call.resolve(out);

                            } catch (Exception e) {

                                call.reject(
                                        "Biometric signing failed"
                                );
                            }
                        }

                        @Override
                        public void onAuthenticationError(
                                int errorCode,
                                @NonNull CharSequence errString
                        ) {

                            call.reject(
                                    "Biometric authentication failed: "
                                            + errString
                            );
                        }
                    };

            BiometricPrompt prompt =
                    new BiometricPrompt(
                            getActivity(),
                            callback
                    );

            BiometricPrompt.PromptInfo info =
                    new BiometricPrompt.PromptInfo.Builder()
                            .setTitle(
                                    registration
                                            ? "Enable DeenWallet biometric login"
                                            : "Biometric login"
                            )
                            .setSubtitle(
                                    registration
                                            ? "Confirm your identity to enable biometric login"
                                            : "Confirm your identity to sign in"
                            )
                            .setNegativeButtonText(
                                    "Cancel"
                            )
                            .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                            )
                            .build();

            prompt.authenticate(info);

        } catch (Exception e) {

            call.reject(
                    "Could not start biometric authentication"
            );
        }
    }

    private SharedPreferences prefs() {

        return getContext()
                .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );
    }

    private KeyStore getKeyStore()
            throws Exception {

        KeyStore keyStore =
                KeyStore.getInstance(
                        "AndroidKeyStore"
                );

        keyStore.load(null);

        return keyStore;
    }
}