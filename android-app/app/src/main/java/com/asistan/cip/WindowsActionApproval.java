package com.asistan.cip;

import android.app.Activity;

/** Produces a Keystore-backed signature after per-use Android biometric approval. */
public interface WindowsActionApproval {
    interface Callback {
        void onResult(byte[] signature, String error);
    }

    void signChallenge(Activity activity, String challenge, Callback callback);
}
