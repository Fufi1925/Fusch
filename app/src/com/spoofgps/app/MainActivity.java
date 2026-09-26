package com.spoofgps.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 42;
    private static final int REQ_NOTIFICATIONS = 43;

    /**
     * Update-Manifest: JSON mit {"version":"x.y","url":"https://...","notes":"..."}
     * Wird beim Start geprueft; bei neuerer Version erscheint ein Update-Pop-up.
     */
    static final String UPDATE_JSON_URL =
            "https://fusch.up.railway.app/update.json";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 2.9 removes the 2.8 overlay entirely, including its saved opt-in.
        Prefs.remove(this, "dynamic_island");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setBackgroundColor(0xFF0B0F17);
        web.setWebViewClient(new WebClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, hasPerm(Manifest.permission.ACCESS_FINE_LOCATION), false);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        SpoofEngine.get().listener = new SpoofEngine.Listener() {
            @Override
            public void onSpoofChanged(boolean running, String error) {
                pushState();
            }
        };
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                pushState();
            }
        }, 800);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(
                        "window.onSystemSettingsReturn && window.onSystemSettingsReturn();", null);
                // A foreground-service notification may stop a route in the background.
                pushState();
            }
        }, 250);
    }

    @Override
    protected void onDestroy() {
        SpoofEngine.get().listener = null;
        web = null;
        super.onDestroy();
    }

    private void pushState() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                String err = SpoofEngine.get().error == null ? "" : SpoofEngine.get().error;
                String js = "window.onNativeState && window.onNativeState("
                        + SpoofEngine.get().running + ","
                        + JSONObject.quote(err) + ","
                        + SpoofEngine.get().lat + "," + SpoofEngine.get().lng + ");";
                web.evaluateJavascript(js, null);
            }
        });
    }

    private boolean hasPerm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private volatile boolean locBusy = false;

    /** Only called after an explicit tap on the location permission button. */
    private void askLocationPermission() {
        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            notifyPermissionResult();
            return;
        }
        // Android 12+ requires requesting COARSE and FINE together so the
        // system can show its "precise location" choice.
        String[] permissions = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        requestPermissions(permissions, REQ_LOCATION);
    }

    /** Notification permission is separate and optional (Android 13+). */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        } else {
            notifyPermissionResult();
        }
    }

    private void notifyPermissionResult() {
        if (web != null)
            web.evaluateJavascript("window.permsResult && window.permsResult();", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION || requestCode == REQ_NOTIFICATIONS)
            notifyPermissionResult();
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript("(window.appBack?window.appBack():false)",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (!"true".equals(v)) finish();
                        }
                    });
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Injects the geo API key into map tile requests natively so the key
     * never appears inside the WebView/JS layer. Tile URLs pointing at the
     * keyed tile service are re-signed with the real key here.
     */
    private class WebClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            if (!req.isForMainFrame()) return false;
            Uri uri = req.getUrl();
            if (uri != null && "file".equals(uri.getScheme())
                    && "/android_asset/index.html".equals(uri.getPath())) return false;
            // Never navigate the WebView (and its native bridge) to remote content.
            if (uri != null && "https".equals(uri.getScheme()))
                launchSetting(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null || !"tiles.stadiamaps.com".equals(u.getHost())) return null;
            if (!Prefs.bool(getApplicationContext(), "map_key_tiles", true)) return null;
            String key = Geo.currentKey(getApplicationContext());
            if (key.isEmpty()) return null;
            HttpURLConnection c = null;
            try {
                StringBuilder sb = new StringBuilder("https://tiles.stadiamaps.com").append(u.getPath());
                sb.append("?api_key=").append(Uri.encode(key));
                c = (HttpURLConnection) new URL(sb.toString()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code != 200) {
                    final boolean rejected = (code == 401 || code == 403);
                    if (rejected && web != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (web != null) web.evaluateJavascript(
                                        "window.tileAuthFailed && window.tileAuthFailed();", null);
                            }
                        });
                    }
                    return null;
                }
                String type = c.getContentType();
                if (type == null) type = "image/png";
                InputStream in = c.getInputStream();
                WebResourceResponse resp = new WebResourceResponse(type, null, in);
                return resp;
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) {
                    try { /* stream ownership passes to WebView */ } catch (Exception ignored) {}
                }
            }
        }
    }

    private boolean launchSetting(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "SpoofGPS/1.2 (Android)");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private class Bridge {

        @JavascriptInterface
        public String startSpoof(final double lat, final double lng, final String name) {
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return "NEED_PERMS";
            String err = SpoofEngine.get().startStatic(getApplicationContext(), lat, lng, name);
            if (err == null) {
                Intent i = new Intent(getApplicationContext(), SpoofService.class);
                getApplicationContext().startForegroundService(i);
                pushState();
                return "OK";
            }
            return "ERR:" + err;
        }

        @JavascriptInterface
        public String startRoute(final String pts, final double speedKmh, final boolean loop, final String name) {
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return "NEED_PERMS";
            String err = SpoofEngine.get().startRoute(getApplicationContext(), pts, speedKmh, loop, name);
            if (err == null) {
                Intent i = new Intent(getApplicationContext(), SpoofService.class);
                getApplicationContext().startForegroundService(i);
                pushState();
                return "OK";
            }
            return err;
        }

        @JavascriptInterface
        public String routeCalc(double sla, double slo, double ela, double elo, String profile) {
            return Router.calc(sla, slo, ela, elo, profile);
        }

        /** Restore an already-running route when the WebView/Activity is recreated. */
        @JavascriptInterface
        public String activeRoute() {
            SpoofEngine.StatusState s = SpoofEngine.get().statusState();
            if (!s.running || !s.route) return "";
            String pts = Prefs.str(getApplicationContext(), "route_json", "");
            if (pts.isEmpty()) return "";
            try {
                JSONObject j = new JSONObject();
                j.put("points", new JSONArray(pts));
                j.put("loop", s.loop);
                j.put("speed", s.speedMs * 3.6);
                j.put("total", s.totalMeters);
                j.put("startedAt", s.startedAtMs);
                return j.toString();
            } catch (Exception ignored) { return ""; }
        }

        /** Current state for the in-app activity screen; no system overlay. */
        @JavascriptInterface
        public String activeStatus() {
            SpoofEngine.StatusState s = SpoofEngine.get().statusState();
            if (!s.running) return "";
            try {
                JSONObject j = new JSONObject();
                j.put("route", s.route);
                j.put("loop", s.loop);
                j.put("name", s.name == null ? "" : s.name);
                j.put("startedAt", s.startedAtMs);
                j.put("totalMeters", s.totalMeters);
                j.put("doneMeters", s.doneMeters);
                j.put("speedMs", s.speedMs);
                return j.toString();
            } catch (Exception ignored) { return ""; }
        }

        /** Aktuelle Engine-Position: "lat,lng" oder "". */
        @JavascriptInterface
        public String getPos() {
            SpoofEngine e = SpoofEngine.get();
            if (!e.running) return "";
            return e.lat + "," + e.lng;
        }

        @JavascriptInterface
        public void stopSpoof() {
            SpoofEngine.get().stop(getApplicationContext());
            pushState();
        }

        @JavascriptInterface
        public boolean isSpoofing() {
            return SpoofEngine.get().running;
        }

        @JavascriptInterface
        public boolean hasLocPerm() {
            return hasPerm(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        @JavascriptInterface
        public boolean hasNotifPerm() {
            return Build.VERSION.SDK_INT < 33 || hasPerm(Manifest.permission.POST_NOTIFICATIONS);
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { askLocationPermission(); }
            });
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { askNotificationPermission(); }
            });
        }

        @JavascriptInterface
        public void setSettings(final String json) {
            try {
                JSONObject o = new JSONObject(json);
                Context c = getApplicationContext();
                Prefs.put(c, "mock_network", o.optBoolean("mock_network", true));
                Prefs.put(c, "jitter", o.optBoolean("jitter", true));
                Prefs.put(c, "interval_ms", o.optInt("interval_ms", 1000));
                Prefs.put(c, "accuracy_m", o.optInt("accuracy_m", 5));
                Prefs.put(c, "notif_coords", o.optBoolean("notif_coords", true));
                Prefs.put(c, "notif_timer", o.optBoolean("notif_timer", true));
                Prefs.put(c, "map_key_tiles", o.optBoolean("map_key_tiles", true));
                Prefs.put(c, "autostart", o.optBoolean("autostart", false));
                Prefs.put(c, "keep_alive", o.optBoolean("keep_alive", true));
                SpoofEngine.get().reconfigure();
                if (SpoofEngine.get().running) {
                    Intent i = new Intent(c, SpoofService.class).setAction(SpoofService.ACTION_REFRESH);
                    try { c.startService(i); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        /** Geocoding happens natively; the API key never enters the WebView. */
        @JavascriptInterface
        public String geoSearch(String q) {
            return Geo.search(getApplicationContext(), q);
        }

        @JavascriptInterface
        public String geoReverse(double lat, double lng) {
            return Geo.reverse(getApplicationContext(), lat, lng);
        }

        @JavascriptInterface
        public void setCustomKey(String k) {
            Sec.put(getApplicationContext(), "geokey", k == null ? "" : k.trim());
        }

        @JavascriptInterface
        public void clearCustomKey() {
            Sec.put(getApplicationContext(), "geokey", "");
        }

        /** CUSTOM / BUILTIN / NONE - status only, never the key. */
        @JavascriptInterface
        public String geoStatus() {
            return Geo.status(getApplicationContext());
        }

        @JavascriptInterface
        public String geoTest() {
            return Geo.test(getApplicationContext());
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "2.9";
            }
        }

        @JavascriptInterface
        public int appCode() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public long getStartedAt() {
            return SpoofEngine.get().startedAtMs;
        }

        @JavascriptInterface
        public String storeGet() {
            return Prefs.str(getApplicationContext(), "kv_json", "{}");
        }

        @JavascriptInterface
        public void storeSet(final String j) {
            Prefs.put(getApplicationContext(), "kv_json", j == null ? "{}" : j);
        }

        /** Returns raw update manifest JSON (cache-busted), or "" if unavailable. */
        @JavascriptInterface
        public String checkUpdate() {
            String s = httpGet(UPDATE_JSON_URL + "?t=" + System.currentTimeMillis());
            return s == null ? "" : s;
        }

        @JavascriptInterface
        public void openUrl(final String u) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void deviceLocation() {
            if (locBusy) return;
            try {
                LocationManager lmgr = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    jsToPage("window.onDeviceLocation && window.onDeviceLocation(null);");
                    return;
                }
                // FUSED (Google Play Services) zuerst – bleibt beim Spoofen NICHT gemockt
                java.util.ArrayList<String> provs = new java.util.ArrayList<>();
                if (Build.VERSION.SDK_INT >= 31 && lmgr.getAllProviders().contains(LocationManager.FUSED_PROVIDER))
                    provs.add(LocationManager.FUSED_PROVIDER);
                for (String p : lmgr.getAllProviders()) {
                    if (LocationManager.PASSIVE_PROVIDER.equals(p) || provs.contains(p)) continue;
                    provs.add(p);
                }
                android.location.Location best = null;
                for (String p : provs) {
                    try {
                        android.location.Location l = lmgr.getLastKnownLocation(p);
                        if (l != null && isMockL(l)) continue; // Mock-Reste konsequent verwerfen
                        best = betterLoc(best, l);
                    } catch (Exception ignored) {}
                }
                if (best != null && System.currentTimeMillis() - best.getTime() <= 120000L) {
                    sendLoc(best);
                    return;
                }
                locBusy = true;
                final LocationManager lmF = lmgr;
                final android.location.Location[] box = new android.location.Location[]{best};
                final boolean[] done = {false};
                final android.location.LocationListener[] ls = new android.location.LocationListener[1];
                ls[0] = new android.location.LocationListener() {
                    @Override public void onLocationChanged(android.location.Location l) {
                        if (l == null || isMockL(l)) return; // nur ECHTE Positionen
                        box[0] = betterLoc(box[0], l);
                        // Sofort liefern, sobald ein guter ECHTER Fix da ist (nicht gemockt, ≤75 m)
                        if (!done[0] && !isMockL(l) && l.getAccuracy() <= 75f) {
                            done[0] = true;
                            locBusy = false;
                            try { lmF.removeUpdates(ls[0]); } catch (Exception ignored) {}
                            sendLoc(box[0]);
                        }
                    }
                    @Override public void onStatusChanged(String pr, int st, android.os.Bundle ex) {}
                    @Override public void onProviderEnabled(String pr) {}
                    @Override public void onProviderDisabled(String pr) {}
                };
                boolean any = false;
                for (String p : provs) {
                    try {
                        if (lmgr.isProviderEnabled(p)) { lmgr.requestSingleUpdate(p, ls[0], getMainLooper()); any = true; }
                    } catch (Exception ignored) {}
                }
                final boolean anyF = any;
                new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        if (done[0]) return;
                        done[0] = true;
                        locBusy = false;
                        try { if (anyF) lmF.removeUpdates(ls[0]); } catch (Exception ignored) {}
                        sendLoc(box[0]);
                    }
                }, anyF ? 10000L : 300L);
            } catch (Exception e) {
                locBusy = false;
                jsToPage("window.onDeviceLocation && window.onDeviceLocation(null);");
            }
        }

        private android.location.Location betterLoc(android.location.Location a, android.location.Location b) {
            if (a == null) return b;
            if (b == null) return a;
            boolean am = isMockL(a), bm = isMockL(b);
            if (am != bm) return am ? b : a; // echte Position gewinnt IMMER über die gespoofte
            return b.getTime() > a.getTime() ? b : a;
        }

        private boolean isMockL(android.location.Location l) {
            return Build.VERSION.SDK_INT >= 31 ? l.isMock() : l.isFromMockProvider();
        }

        private void sendLoc(android.location.Location l) {
            if (l == null) {
                jsToPage("window.onDeviceLocation && window.onDeviceLocation(null);");
                return;
            }
            boolean mock = isMockL(l);
            int acc = Math.round(l.getAccuracy());
            jsToPage("window.onDeviceLocation && window.onDeviceLocation({lat:" + l.getLatitude()
                    + ",lng:" + l.getLongitude() + ",mock:" + mock + ",acc:" + acc + "});");
        }

        @JavascriptInterface
        public boolean battOpt() {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void requestBattOpt() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Uri app = Uri.parse("package:" + getPackageName());
                    if (battOpt() && launchSetting(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                        return;
                    // First try Android's dialog for THIS app. Some manufacturers
                    // omit it; then open the battery optimization list or app info.
                    if (launchSetting(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app)))
                        return;
                    if (launchSetting(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                        return;
                    launchSetting(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
                }
            });
        }

        @JavascriptInterface
        public boolean mockAllowed() {
            try {
                android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                int op = Build.VERSION.SDK_INT >= 29
                        ? ops.unsafeCheckOpNoThrow("android:mock_location", android.os.Process.myUid(), getPackageName())
                        : ops.checkOpNoThrow("android:mock_location", android.os.Process.myUid(), getPackageName());
                return op == android.app.AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) {
                return true;
            }
        }

        @JavascriptInterface
        public void openDevSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!launchSetting(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))
                        launchSetting(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    launchSetting(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        private void jsToPage(final String code) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web != null) web.evaluateJavascript(code, null);
                }
            });
        }
    }
}
