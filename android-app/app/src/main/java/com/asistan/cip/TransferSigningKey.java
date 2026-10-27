package com.asistan.cip;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

final class TransferSigningKey {
    private static final String KEYSTORE_ALIAS = "cip_transfer_signing_p256";

    String publicKeyPem() throws Exception {
        java.security.PublicKey publicKey = getOrCreateKeyPair(true).getPublic();
        String body = Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    Signature createSignature(String challenge) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        try {
            signature.initSign(getOrCreateKeyPair(false).getPrivate());
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException invalidated) {
            throw new IllegalStateException(
                    "Biyometrik kayıt değişti; bilgisayarla yeniden eşleşmen gerekiyor.", invalidated);
        }
        signature.update(challenge.getBytes(StandardCharsets.UTF_8));
        return signature;
    }

    private java.security.KeyPair getOrCreateKeyPair(boolean replaceInvalidated) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.cert.Certificate certificate = keyStore.getCertificate(KEYSTORE_ALIAS);
        java.security.Key privateKey;
        try {
            privateKey = keyStore.getKey(KEYSTORE_ALIAS, null);
        } catch (java.security.UnrecoverableKeyException invalidated) {
            if (!replaceInvalidated) {
                throw new IllegalStateException(
                        "Biyometrik kayıt değişti; bilgisayarla yeniden eşleşmen gerekiyor.", invalidated);
            }
            keyStore.deleteEntry(KEYSTORE_ALIAS);
            certificate = null;
            privateKey = null;
        }
        if (certificate != null && privateKey instanceof java.security.PrivateKey) {
            return new java.security.KeyPair(certificate.getPublicKey(), (java.security.PrivateKey) privateKey);
        }
        if (!replaceInvalidated) {
            throw new IllegalStateException(
                    "Aktarım imza anahtarı bulunamadı; bilgisayarla yeniden eşleşmen gerekiyor.");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(-1);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            spec.setInvalidatedByBiometricEnrollment(true);
        }
        generator.initialize(spec.build());
        return generator.generateKeyPair();
    }
}
