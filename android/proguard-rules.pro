# --- Foxit plugin hardening rules ---------------------------------------

# CRITICAL FIX: previously this was
#   -keep,allowobfuscation class com.foxit.flutterfoxitpdf.NativeCrypto
# "allowobfuscation" still lets R8 RENAME the class, but
# foxit_native_crypto.cpp calls:
#   env->FindClass("com/foxit/flutterfoxitpdf/NativeCrypto")
# with that literal string. After a renamed release build, FindClass()
# returns null, RegisterNatives() is never called, and JNI_OnLoad returns
# JNI_ERR -- silently breaking every native decrypt call in production.
# The class has zero logic in it, so the cost of keeping its name
# unobfuscated is negligible; do NOT reintroduce allowobfuscation here
# unless you also update FindClass() to read the obfuscated name from
# mapping.txt at build time (not worth the fragility).
-keep class com.foxit.flutterfoxitpdf.NativeCrypto
# Method names/signatures still don't need keeping -- they are bound
# manually via RegisterNatives, not the Java_pkg_Class_method convention,
# so R8 is free to rename nativeDecryptLic/nativeObfuscationDecrypt.
-keepclassmembers class com.foxit.flutterfoxitpdf.NativeCrypto {
    static <methods>;
}

# Strip any stray debug/verbose/info logging from release builds as a
# backstop, in case a Log.d()/Log.v()/Log.i() call is ever reintroduced
# around sensitive data during future edits.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Scrub real source file names from stack traces in release builds (crash
# reports still get a line number, just not "PDFReaderActivity.java" etc).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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

# NOTE: these rules only govern R8's pass over *this library module*.
# Make sure build.gradle also exposes them via consumerProguardFiles so
# the app-level R8 pass (when this module is bundled into the final APK)
# doesn't re-break the NativeCrypto lookup. See build.gradle.