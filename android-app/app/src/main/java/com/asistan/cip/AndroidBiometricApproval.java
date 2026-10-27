package com.asistan.cip;

import android.app.Activity;
import android.os.Build;
import android.os.CancellationSignal;

public final class AndroidBiometricApproval implements WindowsActionApproval {
    @Override
    public void requestApproval(Activity activity, String reason, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onResult(false, "Bu Android sürümünde biyometrik onay desteklenmiyor.");
            return;
        }
        if (activity.checkSelfPermission(android.Manifest.permission.USE_BIOMETRIC)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            callback.onResult(false, "Android biyometrik onayı kullanılamıyor.");
            return;
        }
        try {
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(activity)
                            .setTitle("Windows işlemini onayla")
                            .setSubtitle(reason)
                            .setDescription("İşlem yalnızca bu onaydan sonra gönderilebilir.")
                            .setNegativeButton("İptal", activity.getMainExecutor(),
                                    (dialog, which) -> callback.onResult(false, "İşlem iptal edildi."))
                            .build();
            prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            callback.onResult(true, null);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            callback.onResult(false, errString.toString());
                        }
                    });
        } catch (RuntimeException error) {
            callback.onResult(false, "Biyometrik onay başlatılamadı: " + error.getMessage());
        }
    }
}
