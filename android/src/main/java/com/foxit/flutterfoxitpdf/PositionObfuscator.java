package com.foxit.flutterfoxitpdf;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class PositionObfuscator {

    private final String key;
    private final boolean base64EncodeOutput;

    public PositionObfuscator(String key, boolean base64EncodeOutput) {
        this.key = key;
        this.base64EncodeOutput = base64EncodeOutput;
    }

    private int seedFromKeyAndLength(String key, int length) {
        byte[] hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        int seed = ((hash[0] & 0xFF) << 24)
                | ((hash[1] & 0xFF) << 16)
                | ((hash[2] & 0xFF) << 8)
                | (hash[3] & 0xFF);

        seed ^= length * 0x9e3779b1;
        return seed;
    }

    private int lcgNext(int state) {
        return state * 1_664_525 + 1_013_904_223;
    }

    private int[] permutation(int length) {
        int[] perm = new int[length];
        for (int i = 0; i < length; i++) {
            perm[i] = i;
        }
        if (length <= 1) {
            return perm;
        }

        int state = seedFromKeyAndLength(key, length);

        for (int i = length - 1; i >= 1; i--) {
            state = lcgNext(state);
            int j = (state >>> 1) % (i + 1);
            int tmp = perm[i];
            perm[i] = perm[j];
            perm[j] = tmp;
        }

        return perm;
    }

    private static int[] toCodePoints(String input) {
        return input.codePoints().toArray();
    }

    private static String fromCodePoints(int[] cps) {
        StringBuilder sb = new StringBuilder(cps.length);
        for (int cp : cps) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /**
     * NOTE: throws IllegalArgumentException if base64EncodeOutput is true
     * and `obfuscated` is not valid Base64 (e.g. a decrypt upstream failed
     * and produced garbage). Callers must catch this -- see
     * PDFReaderActivity#openDocument, which wraps this call and fails
     * cleanly instead of letting the exception crash the activity.
     */
    public String deobfuscate(String obfuscated) {
        if (obfuscated == null || obfuscated.isEmpty()) {
            return obfuscated;
        }

        String decoded;
        if (base64EncodeOutput) {
            byte[] bytes = Base64.decode(obfuscated, Base64.DEFAULT);
            decoded = new String(bytes, StandardCharsets.UTF_8);
        } else {
            decoded = obfuscated;
        }

        int[] cps = toCodePoints(decoded);
        int[] perm = permutation(cps.length);

        int[] original = new int[cps.length];
        for (int dest = 0; dest < perm.length; dest++) {
            original[perm[dest]] = cps[dest];
        }

        return fromCodePoints(original);
    }
}