package com.spoofgps.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Echte Straßen-Routen via OSRM (Open Source Routing Machine).
 * Profile: car / bike / foot über die öffentlichen OSM-DE-Server,
 * Fallback: OSRM-Demo (driving). Gibt normalisiertes JSON zurück:
 * {"ok":true,"points":[[lat,lng],...],"dist":m,"dur":s}
 */
public final class Router {
    private Router() {}

    public static String calc(double la1, double lo1, double la2, double lo2, String profile) {
        String prof = "car".equals(profile) ? "routed-car" :
                "bike".equals(profile) ? "routed-bike" :
                "foot".equals(profile) ? "routed-foot" : "routed-car";
        String[] urls = {
                "https://routing.openstreetmap.de/" + prof + "/route/v1/driving/"
                        + lo1 + "," + la1 + ";" + lo2 + "," + la2
                        + "?overview=full&geometries=geojson&alternatives=false&steps=false",
                "https://router.project-osrm.org/route/v1/driving/"
                        + lo1 + "," + la1 + ";" + lo2 + "," + la2
                        + "?overview=full&geometries=geojson&alternatives=false&steps=false"
        };
        for (String u : urls) {
            try {
                String s = http(u);
                if (s == null) continue;
                JSONObject o = new JSONObject(s);
                if (!"Ok".equals(o.optString("code"))) continue;
                JSONArray routes = o.getJSONArray("routes");
                if (routes.length() == 0) continue;
                JSONObject r = routes.getJSONObject(0);
                JSONArray coords = r.getJSONObject("geometry").getJSONArray("coordinates");
                int n = coords.length();
                if (n < 2) continue;
                // Punktanzahl begrenzen (Engine interpoliert selbst)
                int maxPts = 3000;
                int step = n > maxPts ? (int) Math.ceil(n / (double) maxPts) : 1;
                JSONArray pts = new JSONArray();
                for (int i = 0; i < n; i += step) {
                    JSONArray c = coords.getJSONArray(i);
                    JSONArray p = new JSONArray();
                    p.put(c.getDouble(1)); // lat
                    p.put(c.getDouble(0)); // lng
                    pts.put(p);
                }
                // sicherstellen, dass das Ziel enthalten ist
                JSONArray lastC = coords.getJSONArray(n - 1);
                JSONArray lastP = new JSONArray();
                lastP.put(lastC.getDouble(1));
                lastP.put(lastC.getDouble(0));
                pts.put(lastP);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("points", pts);
                out.put("dist", r.optDouble("distance", 0));
                out.put("dur", r.optDouble("duration", 0));
                return out.toString();
            } catch (Exception ignored) {}
        }
        return "{\"ok\":false}";
    }

    private static String http(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Fusch/1.5 (Android)");
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
}
