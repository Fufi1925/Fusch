package com.spoofgps.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

/**
 * Tiny obfuscating store for sensitive strings (like a user-supplied API key).
 * Values are XOR-masked before they touch disk and are NEVER returned to the
 * WebView/UI layer.
 */
public final class Sec {
    private Sec() {}

    private static final byte[] M = {(byte) 0x5A, (byte) 0x3C, (byte) 0x77, (byte) 0x11,
            (byte) 0x9E, (byte) 0x42, (byte) 0x6D, (byte) 0xA8};

    private static String mask(String s) {
        try {
            byte[] b = s.getBytes("UTF-8");
            for (int i = 0; i < b.length; i++) b[i] ^= M[i % M.length];
            return Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static String unmask(String s) {
        try {
            byte[] b = Base64.decode(s, Base64.NO_WRAP);
            for (int i = 0; i < b.length; i++) b[i] ^= M[i % M.length];
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static void put(Context c, String k, String v) {
        sp(c).edit().putString(k, mask(v == null ? "" : v)).apply();
    }

    public static String get(Context c, String k) {
        String s = sp(c).getString(k, "");
        if (s == null || s.isEmpty()) return "";
        String v = unmask(s);
        return v == null ? "" : v;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("sec", Context.MODE_PRIVATE);
    }
}
