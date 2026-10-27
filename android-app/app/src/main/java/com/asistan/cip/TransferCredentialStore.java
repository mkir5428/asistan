package com.asistan.cip;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TransferCredentialStore {
    private static final String PREFERENCE_KEY = "encrypted_transfer_credentials";
    private static final String KEYSTORE_ALIAS = "cip_transfer_credentials";
    private final SharedPreferences preferences;

    TransferCredentialStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    void save(String serverUrl, String pairingId, String deviceToken) throws Exception {
        JSONObject credentials = new JSONObject();
        credentials.put("server_url", serverUrl);
        credentials.put("pairing_id", pairingId);
        credentials.put("device_token", deviceToken);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
        byte[] ciphertext = cipher.doFinal(credentials.toString().getBytes(StandardCharsets.UTF_8));
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        if (!preferences.edit().putString(PREFERENCE_KEY, encrypted).commit()) {
            throw new IllegalStateException("Aktarım eşleşme bilgileri güvenli biçimde kaydedilemedi.");
        }
    }

    TransferCredentials read() {
        String stored = preferences.getString(PREFERENCE_KEY, "");
        if (stored.isEmpty()) {
            return null;
        }
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("Kayıtlı aktarım eşleşme bilgileri geçersiz.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
            JSONObject json = new JSONObject(new String(
                    cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8));
            String serverUrl = json.getString("server_url");
            String pairingId = json.getString("pairing_id");
            String deviceToken = json.getString("device_token");
            if (serverUrl.isEmpty() || pairingId.isEmpty() || deviceToken.isEmpty()) {
                throw new IllegalStateException("Kayıtlı aktarım eşleşme bilgileri eksik.");
            }
            return new TransferCredentials(serverUrl, pairingId, deviceToken);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Aktarım eşleşme bilgileri çözülemedi; telefonu yeniden eşleştir.", error);
        }
    }

    void clear() throws Exception {
        if (!preferences.edit().remove(PREFERENCE_KEY).commit()) {
            throw new IllegalStateException("Transfer credentials could not be cleared.");
        }
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS);
        }
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
