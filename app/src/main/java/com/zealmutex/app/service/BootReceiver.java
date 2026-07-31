package com.zealmutex.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts maintenance after boot or an in-place ZealMutex update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            MonitorService.start(context);
        }
    }
}
