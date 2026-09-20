package com.spoofgps.app;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings + spoof state storage (SharedPreferences). */
public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("spoof", Context.MODE_PRIVATE);
    }

    public static boolean bool(Context c, String k, boolean d) {
        return sp(c).getBoolean(k, d);
    }

    public static int num(Context c, String k, int d) {
        return sp(c).getInt(k, d);
    }

    public static long lng(Context c, String k, long d) {
        return sp(c).getLong(k, d);
    }

    public static String str(Context c, String k, String d) {
        String v = sp(c).getString(k, d);
        return v == null ? d : v;
    }

    public static void put(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }

    public static void put(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }

    public static void put(Context c, String k, long v) {
        sp(c).edit().putLong(k, v).apply();
    }

    public static void put(Context c, String k, String v) {
        sp(c).edit().putString(k, v == null ? "" : v).apply();
    }
}
