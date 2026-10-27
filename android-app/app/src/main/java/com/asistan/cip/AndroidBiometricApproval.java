package com.asistan.cip;

import android.app.Activity;
import android.os.Build;
import android.os.CancellationSignal;

import java.security.Signature;

/**
 * Signs a transfer challenge only after a per-use biometric prompt unlocks the Keystore key.
 */
public final class AndroidBiometricApproval implements WindowsActionApproval {
    private final TransferSigningKey signingKey = new TransferSigningKey();

    @Override
    public void signChallenge(Activity activity, String challenge, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onResult(null, "Biyometrik imza için Android 9 veya üzeri gerekir.");
            return;
        }
        if (activity.checkSelfPermission(android.Manifest.permission.USE_BIOMETRIC)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            callback.onResult(null, "Android biyometrik onayı kullanılamıyor.");
            return;
        }
        String[] fields = challenge == null ? new String[0] : challenge.split("\n", -1);
        if (fields.length != 9 || !"cip-transfer-v1".equals(fields[0])) {
            callback.onResult(null, "Aktarım challenge biçimi geçersiz.");
            return;
        }
        try {
            Signature signature = signingKey.createSignature(challenge);
            String direction = "pc_to_phone".equals(fields[4])
                    ? "Bilgisayardan telefona" : "Telefondan bilgisayara";
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(activity)
                            .setTitle("Dosya aktarımını onayla")
                            .setSubtitle("Bu işlem için bir defalık imza oluşturulacak.")
                            .setDescription(direction + " · " + fields[5] + " · " + fields[6] + " bayt")
                            .setNegativeButton("İptal", activity.getMainExecutor(),
                                    (dialog, which) -> callback.onResult(null, "Biyometrik onay iptal edildi."))
                            .build();
            prompt.authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(signature),
                    new CancellationSignal(), activity.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            try {
                                Signature authenticated = result.getCryptoObject().getSignature();
                                callback.onResult(authenticated.sign(), null);
                            } catch (Exception error) {
                                callback.onResult(null, "Biyometrik imza üretilemedi: " + error.getMessage());
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            callback.onResult(null, errString.toString());
                        }
                    });
        } catch (Exception error) {
            callback.onResult(null, "Biyometrik imza başlatılamadı: " + error.getMessage());
        }
    }

    String publicKeyPem() throws Exception {
        return signingKey.publicKeyPem();
    }
}
