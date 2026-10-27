package com.asistan.cip;

import android.app.Activity;

/** Approval boundary for future Windows actions; no server action is invoked here. */
public interface WindowsActionApproval {
    interface Callback {
        void onResult(boolean approved, String error);
    }

    void requestApproval(Activity activity, String reason, Callback callback);
}
