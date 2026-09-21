package com.spoofgps.app;

import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Injects a mock GPS (+ optional network) location on a configurable interval.
 * Supports static teleports and route simulation (movement along waypoints).
 * Requires the app to be selected as "mock location app" in developer options.
 * State is persisted so it survives process death and reboot.
 */
public class SpoofEngine {

    public interface Listener {
        void onSpoofChanged(boolean running, String error);
    }

    private static final SpoofEngine INSTANCE = new SpoofEngine();
    public static SpoofEngine get() { return INSTANCE; }

    public volatile boolean running = false;
    public volatile String error = null;
    public volatile double lat, lng;
    public volatile String name = null;
    public volatile long startedAtMs = 0L;
    public volatile Listener listener = null;

    // ---- route state ----
    public volatile boolean routeMode = false;
    public volatile boolean routeLoop = false;
    public volatile double speedMs = 1.388; // ~5 km/h
    private final List<double[]> rPts = new ArrayList<>(); // {lat,lng}
    private int rIdx = 0;
    private double rProg = 0; // meters into current segment
    private long rLast = 0;

    private LocationManager lm;
    private Context appCtx;
    private final Set<String> added = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<String> wanted() {
        List<String> ps = new ArrayList<>();
        ps.add(LocationManager.GPS_PROVIDER);
        if (Prefs.bool(appCtx, "mock_network", true)) ps.add(LocationManager.NETWORK_PROVIDER);
        return ps;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            synchronized (SpoofEngine.this) {
                try {
                    if (routeMode) advanceRoute();
                    pushLocation();
                    long interval = Math.max(250, Prefs.num(appCtx, "interval_ms", 1000));
                    handler.postDelayed(this, interval);
                } catch (Exception e) {
                    error = friendly(e);
                    running = false;
                    handler.removeCallbacks(this);
                    cleanupProviders();
                    stopService();
                    Listener l = listener;
                    if (l != null) l.onSpoofChanged(false, error);
                }
            }
        }
    };

    /** @return null on success, otherwise an error code */
    public synchronized String start(Context ctx, double la, double lo, String nm) {
        error = null;
        try {
            appCtx = ctx.getApplicationContext();
            lm = (LocationManager) appCtx.getSystemService(Context.LOCATION_SERVICE);
            setupProviders();
            lat = la;
            lng = lo;
            name = (nm == null || nm.trim().isEmpty()) ? null : nm.trim();
            startedAtMs = System.currentTimeMillis();
            running = true;
            handler.removeCallbacks(tick);
            handler.post(tick);
            return null;
        } catch (Exception e) {
            running = false;
            handler.removeCallbacks(tick);
            cleanupProviders();
            error = friendly(e);
            return error;
        }
    }

    /** Starts a route simulation. ptsJson: [[lat,lng],[lat,lng],...] */
    public synchronized String startRoute(Context ctx, String ptsJson, double speedKmh,
                                          boolean loop, String nm) {
        try {
            JSONArray arr = new JSONArray(ptsJson);
            if (arr.length() < 2) return "ERR:ROUTE_TOO_SHORT";
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                pts.add(new double[]{p.getDouble(0), p.getDouble(1)});
            }
            rPts.clear();
            rPts.addAll(pts);
            rIdx = 0;
            rProg = 0;
            rLast = 0;
            speedMs = Math.max(0.3, speedKmh / 3.6);
            routeLoop = loop;
            routeMode = true;
            String err = start(ctx, pts.get(0)[0], pts.get(0)[1], nm);
            if (err != null) {
                routeMode = false;
                rPts.clear();
                return err;
            }
            Context c = appCtx;
            Prefs.put(c, "route_json", ptsJson);
            Prefs.put(c, "route_speed", Double.doubleToRawLongBits(speedKmh));
            Prefs.put(c, "route_loop", loop);
            return null;
        } catch (Exception e) {
            routeMode = false;
            rPts.clear();
            return "ERR:BADROUTE";
        }
    }

    /** Restores the persisted spoof or route (after reboot / process death). */
    public synchronized String restore(Context ctx) {
        if (!Prefs.bool(ctx, "active", false)) return "INACTIVE";
        String rj = Prefs.str(ctx, "route_json", "");
        if (!rj.isEmpty()) {
            double sp = Double.longBitsToDouble(Prefs.lng(ctx, "route_speed",
                    Double.doubleToRawLongBits(5.0)));
            return startRoute(ctx, rj, sp, Prefs.bool(ctx, "route_loop", false),
                    Prefs.str(ctx, "last_name", "Route"));
        }
        double la = Double.longBitsToDouble(Prefs.lng(ctx, "last_lat", 0L));
        double lo = Double.longBitsToDouble(Prefs.lng(ctx, "last_lng", 0L));
        if (la == 0.0 && lo == 0.0) return "INACTIVE";
        return start(ctx, la, lo, Prefs.str(ctx, "last_name", ""));
    }

    private static void persist(Context c, double la, double lo, String nm) {
        Prefs.put(c, "active", true);
        Prefs.put(c, "last_lat", Double.doubleToRawLongBits(la));
        Prefs.put(c, "last_lng", Double.doubleToRawLongBits(lo));
        Prefs.put(c, "last_name", nm == null ? "" : nm);
    }

    // ---- route math ----
    private static double distM(double la1, double lo1, double la2, double lo2) {
        double r = Math.PI / 180;
        double dLa = (la2 - la1) * r, dLo = (lo2 - lo1) * r;
        double x = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(x)));
    }

    private double segLen(int i) {
        if (i < 0 || i >= rPts.size() - 1) return 0;
        double[] a = rPts.get(i), b = rPts.get(i + 1);
        return distM(a[0], a[1], b[0], b[1]);
    }

    private void advanceRoute() {
        int n = rPts.size();
        if (n < 2) return;
        long now = SystemClock.elapsedRealtime();
        if (rLast == 0) rLast = now;
        double dt = (now - rLast) / 1000.0;
        rLast = now;
        double adv = speedMs * dt;
        int guard = 0;
        while (adv > 0 && guard++ < 100000) {
            if (rIdx >= n - 1) {
                if (routeLoop) {
                    rIdx = 0;
                    rProg = 0;
                    continue;
                }
                rIdx = n - 2;
                rProg = segLen(rIdx);
                break;
            }
            double remain = segLen(rIdx) - rProg;
            if (adv < remain) {
                rProg += adv;
                adv = 0;
            } else {
                adv -= remain;
                rIdx++;
                rProg = 0;
            }
        }
        double[] a = rPts.get(rIdx);
        double[] b = rPts.get(Math.min(rIdx + 1, n - 1));
        double L = segLen(rIdx);
        double f = L > 0 ? Math.min(1, rProg / L) : 0;
        lat = a[0] + (b[0] - a[0]) * f;
        lng = a[1] + (b[1] - a[1]) * f;
        persist(appCtx, lat, lng, name);
    }

    /** Apply provider-related settings while a spoof is running. */
    public synchronized void reconfigure() {
        if (!running || lm == null || appCtx == null) return;
        try {
            setupProviders();
            pushLocation();
        } catch (Exception ignored) {}
    }

    private void setupProviders() throws Exception {
        List<String> want = wanted();
        for (String p : want) {
            if (lm.getProvider(p) == null) continue;
            try { lm.removeTestProvider(p); } catch (Exception ignored) {}
            lm.addTestProvider(p, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            lm.setTestProviderEnabled(p, true);
            added.add(p);
        }
        for (String p : new ArrayList<>(added)) {
            if (!want.contains(p)) {
                try { lm.setTestProviderEnabled(p, false); } catch (Exception ignored) {}
                try { lm.removeTestProvider(p); } catch (Exception ignored) {}
                added.remove(p);
            }
        }
    }

    public synchronized void stop(Context ctx) {
        error = null;
        running = false;
        routeMode = false;
        routeLoop = false;
        name = null;
        startedAtMs = 0L;
        rPts.clear();
        rIdx = 0;
        rProg = 0;
        rLast = 0;
        handler.removeCallbacks(tick);
        cleanupProviders();
        Context c = appCtx != null ? appCtx : (ctx != null ? ctx.getApplicationContext() : null);
        kickRealProviders(c);
        if (c != null) {
            Prefs.put(c, "active", false);
            Prefs.put(c, "route_json", "");
            try {
                c.stopService(new Intent(c, SpoofService.class));
            } catch (Exception ignored) {}
        }
        Listener l = listener;
        if (l != null) l.onSpoofChanged(false, null);
    }

    private void stopService() {
        if (appCtx != null) {
            Prefs.put(appCtx, "active", false);
            Prefs.put(appCtx, "route_json", "");
            try { appCtx.stopService(new Intent(appCtx, SpoofService.class)); } catch (Exception ignored) {}
        }
    }

    /** Nach dem Stoppen echte Provider kurz aktivieren, damit das Gerät sofort wieder den echten Standort liefert. */
    private void kickRealProviders(Context c) {
        if (c == null) return;
        try {
            final LocationManager lmF = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lmF == null) return;
            final android.location.LocationListener[] hold = new android.location.LocationListener[1];
            hold[0] = new android.location.LocationListener() {
                @Override public void onLocationChanged(Location l) { }
                @Override public void onStatusChanged(String p, int s, android.os.Bundle e) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            final List<String> provs = new ArrayList<>();
            if (android.os.Build.VERSION.SDK_INT >= 31) provs.add(LocationManager.FUSED_PROVIDER);
            provs.add(LocationManager.GPS_PROVIDER);
            provs.add(LocationManager.NETWORK_PROVIDER);
            boolean any = false;
            for (String p : provs) {
                try {
                    if (lmF.isProviderEnabled(p)) { lmF.requestSingleUpdate(p, hold[0], c.getMainLooper()); any = true; }
                } catch (Exception ignored) {}
            }
            if (any) {
                new Handler(c.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        for (String p : provs) {
                            try { lmF.removeUpdates(hold[0]); } catch (Exception ignored) {}
                        }
                    }
                }, 10000L);
            }
        } catch (Exception ignored) {}
    }

    private void cleanupProviders() {
        for (String p : new ArrayList<>(added)) {
            try { lm.setTestProviderEnabled(p, false); } catch (Exception ignored) {}
            try { lm.removeTestProvider(p); } catch (Exception ignored) {}
        }
        added.clear();
        lm = null;
    }

    private void pushLocation() {
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        boolean jitter = Prefs.bool(appCtx, "jitter", true);
        float acc = Math.max(1f, Prefs.num(appCtx, "accuracy_m", 5));
        for (String p : new ArrayList<>(added)) {
            Location l = new Location(p);
            if (jitter) {
                l.setLatitude(lat + (Math.random() - 0.5) * 0.00004); // ~ +/- 2 m
                l.setLongitude(lng + (Math.random() - 0.5) * 0.00004);
            } else {
                l.setLatitude(lat);
                l.setLongitude(lng);
            }
            l.setAccuracy(p.equals(LocationManager.GPS_PROVIDER) ? acc : Math.max(20f, acc * 4f));
            l.setSpeed((float) (routeMode ? speedMs : 0));
            l.setTime(now);
            l.setElapsedRealtimeNanos(elapsed);
            lm.setTestProviderLocation(p, l);
        }
    }

    private String friendly(Throwable t) {
        if (t instanceof SecurityException) return "NOT_SELECTED";
        return "UNKNOWN:" + t.getClass().getSimpleName();
    }
}
