package com.spoofgps.app;

/**
 * Obfuscated geo credentials. Plaintext exists only transiently in RAM.
 * Do NOT log, serialize or display the result of get().
 */
public final class K {

    private static final int[] P = {70, 20, 226, 150, 236, 159, 87, 177, 102, 217, 188, 8, 12, 210, 212, 90};

    private static final int[] D = {37, 118, 211, 201, 223, 237, 63, 128, 57, 232, 227, 58, 60, 225, 227, 62, 39, 119, 128, 240, 138, 166, 103, 215, 0, 239, 216, 63, 57, 224, 229, 110, 37, 113, 132};

    private K() {}

    public static String get() {
        try {
            byte[] b = new byte[D.length];
            for (int i = 0; i < D.length; i++) {
                b[i] = (byte) (D[i] ^ P[i % P.length]);
            }
            String s = new String(b, "UTF-8");
            java.util.Arrays.fill(b, (byte) 0);
            return s;
        } catch (Exception e) {
            return "";
        }
    }
}
