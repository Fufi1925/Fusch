package com.spoofgps.app;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Geocoding runs entirely inside native code so the API key never reaches the
 * WebView, the HTML/JS layer or any log. If the keyed service rejects the
 * request, the public Nominatim endpoint is used as a silent fallback.
 */
public final class Geo {
    private Geo() {}

    private static long lastKeyed = 0L;

    private static String http(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "SpoofGPS/1.1 (Android)");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
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

    private static boolean okArray(String s) {
        return s != null && s.trim().startsWith("[");
    }

    private static boolean okObj(String s) {
        return s != null && s.trim().startsWith("{") && !s.contains("\"error\"");
    }

    /** Custom key (if set) has priority over the built-in one. */
    public static String currentKey(Context c) {
        String custom = Sec.get(c, "geokey");
        return custom.isEmpty() ? K.get() : custom;
    }

    public static boolean customSet(Context c) {
        return !Sec.get(c, "geokey").isEmpty();
    }

    /** Free tier allows ~1 request/second. */
    private static void throttle() {
        long wait = 1100 - (SystemClock.elapsedRealtime() - lastKeyed);
        if (wait > 0 && wait < 1100) {
            try { Thread.sleep(wait); } catch (Exception ignored) {}
        }
        lastKeyed = SystemClock.elapsedRealtime();
    }

    public static synchronized String search(Context c, String q) {
        String enc = Uri.encode(q == null ? "" : q);
        String key = currentKey(c);
        if (!key.isEmpty()) {
            throttle();
            String s = http("https://geocode.maps.co/search?q=" + enc
                    + "&format=jsonv2&accept-language=de&api_key=" + Uri.encode(key));
            if (okArray(s)) return s;
        }
        return http("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&accept-language=de&q=" + enc);
    }

    public static synchronized String reverse(Context c, double lat, double lng) {
        String key = currentKey(c);
        if (!key.isEmpty()) {
            throttle();
            String s = http("https://geocode.maps.co/reverse?lat=" + lat + "&lon=" + lng
                    + "&format=jsonv2&accept-language=de&api_key=" + Uri.encode(key));
            if (okObj(s)) return s;
        }
        return http("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=de&lat=" + lat + "&lon=" + lng);
    }

    /** CUSTOM / BUILTIN / NONE – never contains the key itself. */
    public static String status(Context c) {
        return customSet(c) ? "CUSTOM" : (K.get().isEmpty() ? "NONE" : "BUILTIN");
    }

    public static String test(Context c) {
        String key = currentKey(c);
        if (!key.isEmpty()) {
            throttle();
            String s = http("https://geocode.maps.co/reverse?lat=52.52&lon=13.405&format=jsonv2&api_key=" + Uri.encode(key));
            if (okObj(s)) return customSet(c) ? "OK:CUSTOM" : "OK:BUILTIN";
        }
        String f = http("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=52.52&lon=13.405");
        return okObj(f) ? "FALLBACK" : "ERR";
    }
}
