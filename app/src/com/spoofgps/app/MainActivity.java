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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 42;

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
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
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
                // Ask for location + notification permission right on first open
                if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
                        || (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS))) {
                    requestNeededPerms();
                }
            }
        }, 800);
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

    private void requestNeededPerms() {
        ArrayList<String> need = new ArrayList<>();
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION))
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS))
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty())
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMS && web != null)
            web.evaluateJavascript("window.permsResult && window.permsResult();", null);
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
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { requestNeededPerms(); }
                });
                return "NEED_PERMS";
            }
            String err = SpoofEngine.get().start(getApplicationContext(), lat, lng, name);
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
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { requestNeededPerms(); }
                });
                return "NEED_PERMS";
            }
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
        public void requestPerms() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { requestNeededPerms(); }
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
                Prefs.put(c, "autostart", o.optBoolean("autostart", true));
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
                return "1.2";
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
                    runOnUiThread(new Runnable() { @Override public void run() { requestNeededPerms(); } });
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
                        if (l == null) return;
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
                }, anyF ? 8000L : 300L);
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
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            }
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
