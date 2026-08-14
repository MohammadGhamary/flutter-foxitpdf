package com.foxit.flutterfoxitpdf;

/**
 * Thin JNI bridge to the native crypto implementation (libfoxitlic.so).
 *
 * All license/token decryption and the position-obfuscation logic now live
 * entirely in native C++ (see native/foxit_native_crypto.cpp). Nothing about
 * the algorithm, salt, or key-derivation scheme appears in the Java/Kotlin
 * bytecode or in the dex file, so a plain jadx/JEB decompile no longer
 * reveals it.
 *
 * Native methods are bound manually via JNI_OnLoad/RegisterNatives in the
 * .so (not via the standard Java_pkg_Class_method naming convention), so the
 * exported symbol table of the .so does not leak which Java method a given
 * native function implements. That means renaming this class via R8 does
 * NOT break the binding -- only the literal fully-qualified class name
 * "com.foxit.flutterfoxitpdf.NativeCrypto" must survive obfuscation, which
 * is handled by a single explicit keep rule in proguard-rules.pro.
 */
final class NativeCrypto {

    static {
        System.loadLibrary("foxitlic");
    }

    private NativeCrypto() {}

    /**
     * Equivalent of the old FlutterFoxitpdfPlugin#decryptLic.
     * Returns null (not an exception) on any failure; callers already treat
     * a null/failed decrypt as a hard error, so this preserves behavior.
     */
    static native String nativeDecryptLic(String encryptionKey, String encryptedToken);

    /**
     * Equivalent of the old ObfuscationUtil#decrypt(encrypted, key, bookId).
     */
    static native String nativeObfuscationDecrypt(String encrypted, String key, int bookId);
}