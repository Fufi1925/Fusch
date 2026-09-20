package com.spoofgps.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

import java.util.Locale;

public class SpoofService extends Service {
    public static final String ACTION_STOP = "com.spoofgps.app.STOP";
    public static final String ACTION_REFRESH = "com.spoofgps.app.REFRESH";
    private static final String CH = "spoof";
    private static final int ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CH, "Spoofing", NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        c.setDescription("Zeigt an, solange ein gefälschter Standort aktiv ist");
        nm.createNotificationChannel(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SpoofEngine.get().stop(this);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Survives process death: system restarted the sticky service
        if (!SpoofEngine.get().running && Prefs.bool(this, "active", false)
                && Prefs.bool(this, "keep_alive", true)) {
            SpoofEngine.get().restore(this);
        }
        if (!SpoofEngine.get().running) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID, build());
        return START_STICKY;
    }

    private Notification build() {
        SpoofEngine e = SpoofEngine.get();
        boolean showCoords = Prefs.bool(this, "notif_coords", true);
        boolean showTimer = Prefs.bool(this, "notif_timer", true);
        String title = (e.name != null && !e.name.isEmpty()) ? e.name : "Spoofing aktiv";
        String coords = String.format(Locale.US, "%.5f, %.5f", e.lat, e.lng);
        String text = showCoords ? coords : "Alle Standort-Apps erhalten diesen Standort";

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, SpoofService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat_pin)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(0xFF0A84FF)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(pOpen);
        if (showCoords) {
            b.setStyle(new Notification.BigTextStyle()
                    .bigText(coords + "\nAlle Apps, die den Standort abfragen, erhalten diese Position."));
        }
        if (showTimer) {
            b.setUsesChronometer(true);
            b.setWhen(e.startedAtMs > 0 ? e.startedAtMs : System.currentTimeMillis());
        }
        b.addAction(new Notification.Action.Builder((Icon) null, "Stoppen", pStop).build());
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
