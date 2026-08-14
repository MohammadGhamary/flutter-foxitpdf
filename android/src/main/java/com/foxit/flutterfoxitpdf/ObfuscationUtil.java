package com.foxit.flutterfoxitpdf;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.util.encoders.Hex;

import android.util.Base64;
import android.util.Log;

/**
 * Shared decryption helper for the bundle-level "obfuscation" scheme keyed
 * by bookId / bookTitle.
 *
 * This is used in two places:
 *  - FlutterFoxitpdfPlugin: to decrypt bookTranslatorE (which is itself an
 *    encrypted string) into the real key used for the SN/Key license
 *    decryption (see FlutterFoxitpdfPlugin.decryptLic). This must run
 *    BEFORE PDFReaderActivity is started, since Library.initialize() needs
 *    to happen before PDFViewCtrl/UIExtensionsManager are constructed.
 *  - PDFReaderActivity: to derive the position-obfuscator key (from
 *    bookName) and the document open password (from bookCategory).
 *
 * Kept in one place so there's a single implementation instead of two
 * copies that could silently drift apart.
 */
final class ObfuscationUtil {
    private static final String TAG = "ObfuscationUtil";

    static final List<Integer> BYTTESPS1 = Arrays.asList(
            73, 77, 91, 39, 75, 74, 75, 39,
            88, 67, 75, 91, 63, 88, 105, 108,
            108, 97, 102, 111
    );

    static final List<Integer> BYTTESPS2 = Arrays.asList(
            73, 77, 91
    );

    private ObfuscationUtil() {}

    /** Convenience overload using the default BYTTESPS1 / BYTTESPS2 tables. */
    static String decrypt(String encrypted, String key, int bookId) {
        return decrypt(encrypted, key, bookId, BYTTESPS1, BYTTESPS2);
    }

    static String decrypt(String encrypted, String key, int bookId, List<Integer> byttesps1, List<Integer> byttesps2) {
        if (encrypted == null || key == null || key.length() < 4) {
            return null;
        }
        try {
            SecretKeySpec skeySpec = new SecretKeySpec(
                    utf8ToHex(key, false).getBytes(StandardCharsets.UTF_8),
                    getOrgPs(bookId, byttesps2)
            );

            IvParameterSpec ivSpec = new IvParameterSpec(
                    utf8ToHex(key.substring(0, 4), true).getBytes(StandardCharsets.UTF_8)
            );

            Cipher ecipher = Cipher.getInstance(getOrgPs(bookId, byttesps1));
            ecipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);

            byte[] raw = Base64.decode(encrypted, Base64.DEFAULT);
            byte[] originalBytes = ecipher.doFinal(raw);

            return new String(originalBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            // Never log `encrypted`/`key`/the decrypted result here — this
            // handles license-related secrets in some call sites.
            Log.e(TAG, "Decryption error", e);
        }
        return null;
    }

    private static String getOrgPs(int bookId, List<Integer> list) {
        StringBuilder ps = new StringBuilder();
        if (list != null) {
            for (int i : list) {
                ps.append(getXorPs(bookId, i));
            }
        }
        return ps.toString();
    }

    private static String getXorPs(int bookId, int value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(Integer.toString(bookId).getBytes(StandardCharsets.UTF_8));
            StringBuilder mdf = new StringBuilder(new BigInteger(1, digest).toString(4));

            while (mdf.length() < 4) {
                mdf.insert(0, "0");
            }

            return new String(Character.toChars(value ^ 8));

        } catch (Exception e) {
            return "";
        }
    }

    private static String utf8ToHex(String str, boolean havePadding) {
        if (str == null) return "";
        StringBuilder hexResult = new StringBuilder();

        str.codePoints().forEach(codePoint -> {
            String ch = new String(Character.toChars(codePoint));
            byte[] utf8 = ch.getBytes(StandardCharsets.UTF_8);
            byte[] hexBytes = Hex.encode(utf8);

            String res = new String(hexBytes, StandardCharsets.UTF_8);
            if (res.length() == 2 && havePadding) {
                res = "00" + res;
            }

            hexResult.append(res);
        });

        return hexResult.toString();
    }
}
