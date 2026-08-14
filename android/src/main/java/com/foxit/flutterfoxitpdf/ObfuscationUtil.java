package com.foxit.flutterfoxitpdf;

import android.util.Log;

/**
 * Public API kept identical to the previous implementation so callers
 * (FlutterFoxitpdfPlugin, PDFReaderActivity) require no changes.
 *
 * The actual AES key/IV derivation and cipher selection now live in native
 * code (see native/foxit_native_crypto.cpp). This class is intentionally a
 * one-line pass-through so nothing about the scheme is left in the dex.
 */
final class ObfuscationUtil {
    private static final String TAG = "ObfuscationUtil";

    private ObfuscationUtil() {}

    static String decrypt(String encrypted, String key, int bookId) {
        if (encrypted == null || key == null || key.length() < 4) {
            return null;
        }
        try {
            return NativeCrypto.nativeObfuscationDecrypt(encrypted, key, bookId);
        } catch (Throwable t) {
            // Never log the key/encrypted payload themselves.
            Log.e(TAG, "Native obfuscation decrypt failed: " + t.getClass().getSimpleName());
            return null;
        }
    }
}