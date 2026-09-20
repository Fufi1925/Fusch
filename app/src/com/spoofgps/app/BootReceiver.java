package com.spoofgps.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores an active spoof after the device has been rebooted
 * (if the user kept "Autostart" enabled).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String a = intent == null ? null : intent.getAction();
            if (a == null) return;
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                    && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
            if (!Prefs.bool(context, "autostart", true)) return;
            if (!Prefs.bool(context, "active", false)) return;
            String err = SpoofEngine.get().restore(context);
            if (err == null) {
                Intent i = new Intent(context, SpoofService.class);
                try { context.startForegroundService(i); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
