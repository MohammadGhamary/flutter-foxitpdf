# --- Foxit plugin hardening rules ---------------------------------------

# NativeCrypto's class NAME must survive obfuscation because the .so calls
# env->FindClass("com/foxit/flutterfoxitpdf/NativeCrypto") by literal string
# in JNI_OnLoad. Method names/signatures do NOT need keeping -- they are
# bound manually via RegisterNatives, not by the Java_pkg_Class_method
# convention, so R8 is free to rename nativeDecryptLic/nativeObfuscationDecrypt.
-keep,allowobfuscation class com.foxit.flutterfoxitpdf.NativeCrypto
-keepclassmembers class com.foxit.flutterfoxitpdf.NativeCrypto {
    static <methods>;
}
# NOTE: if you rename the class itself, update the FindClass() string in
# foxit_native_crypto.cpp to match the *obfuscated* name (check
# app/build/outputs/mapping/release/mapping.txt after a build), or switch to
# -keep class ... NativeCrypto { *; }  (keeps the class name unobfuscated,
# simpler, costs you basically nothing since the class has no logic in it).

# Strip any stray debug/verbose/info logging from release builds as a
# backstop, in case a Log.d()/Log.v()/Log.i() call is ever reintroduced
# around sensitive data during future edits.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# General obfuscation strength. Do not disable obfuscation for this module;
# only keep what Flutter/Foxit SDK genuinely require via their own
# consumer-rules.pro (do not blanket -keep this package).
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-optimizationpasses 5

# Keep Flutter plugin registration entry points (required by the Flutter
# engine's reflection-based plugin registrar on older embeddings).
-keep class com.foxit.flutterfoxitpdf.FlutterFoxitpdfPlugin {
    public <init>(...);
    public void onAttachedToEngine(...);
    public void onDetachedFromEngine(...);
    public void onAttachedToActivity(...);
    public void onDetachedFromActivity(...);
}

# Do NOT add a blanket "-keep class com.foxit.flutterfoxitpdf.** { *; }"
# rule -- that would undo the obfuscation of PDFReaderActivity and
# PositionObfuscator, which have no reflection requirement and should be
# obfuscated normally.
