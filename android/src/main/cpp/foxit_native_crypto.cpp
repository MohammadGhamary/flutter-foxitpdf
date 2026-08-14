// foxit_native_crypto.cpp
//
// Native replacement for FlutterFoxitpdfPlugin#decryptLic and
// ObfuscationUtil#decrypt. Moving this to native code means:
//   - the salt, iteration count, and key-derivation scheme never appear in
//     the dex file (invisible to jadx/JEB static analysis of the APK);
//   - functions are bound with JNI_OnLoad/RegisterNatives instead of the
//     standard Java_pkg_Class_method naming convention, so the .so's
//     exported symbol table does not reveal which native function backs
//     which Java method (inspect with `nm -D libfoxitlic.so` to confirm);
//   - this is still reversible with enough effort (IDA/Ghidra + a live
//     Frida trace of the JNI call), it just raises the cost significantly
//     compared to the previous plain-Java implementation.
//
// Requires mbedTLS (see CMakeLists.txt). Depends only on mbedcrypto.

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>

#include "mbedtls/pkcs5.h"
#include "mbedtls/md.h"
#include "mbedtls/aes.h"
#include "mbedtls/base64.h"

namespace {

// Same constant that used to live in FlutterFoxitpdfPlugin.java. It is no
// safer merely by being in native code, but it is no longer trivially
// visible via a Java-level decompile of the APK.
const char* kHardCodedSalt = "fb0dae6afae2a731bf1398759c4e6567";
constexpr int kIterations = 100000;
constexpr size_t kDerivedKeyLenBytes = 32;

// Zero out a buffer before it goes out of scope. Best-effort hygiene --
// this does not defeat a live memory dump taken while the buffer is in use,
// but it does shrink the window where the key sits in memory.
void secureZero(void* p, size_t n) {
    if (p) {
        volatile uint8_t* vp = static_cast<volatile uint8_t*>(p);
        while (n--) *vp++ = 0;
    }
}

std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return result;
}

jstring utf8ToJstring(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

// Standard Base64, URL-safe alphabet, with '-'/'_' translated to '+'/'/'
// and padding restored, matching Base64.URL_SAFE on the Java side.
bool base64UrlDecode(const std::string& in, std::vector<uint8_t>* out) {
    std::string normalized = in;
    for (char& c : normalized) {
        if (c == '-') c = '+';
        else if (c == '_') c = '/';
    }
    while (normalized.size() % 4 != 0) normalized.push_back('=');

    size_t decodedLen = 0;
    int rc = mbedtls_base64_decode(nullptr, 0, &decodedLen,
                                    reinterpret_cast<const uint8_t*>(normalized.data()),
                                    normalized.size());
    if (rc != 0 && rc != MBEDTLS_ERR_BASE64_BUFFER_TOO_SMALL) return false;

    out->resize(decodedLen);
    size_t written = 0;
    rc = mbedtls_base64_decode(out->data(), out->size(), &written,
                                reinterpret_cast<const uint8_t*>(normalized.data()),
                                normalized.size());
    if (rc != 0) return false;
    out->resize(written);
    return true;
}

// Constant-time comparison for the HMAC tag check.
bool constantTimeEqual(const uint8_t* a, const uint8_t* b, size_t len) {
    uint8_t diff = 0;
    for (size_t i = 0; i < len; i++) diff |= a[i] ^ b[i];
    return diff == 0;
}

// Mirrors ObfuscationUtil#utf8ToHex: for each Unicode code point in `str`,
// hex-encode its UTF-8 byte sequence; if havePadding and that sequence was
// a single byte (2 hex chars), left-pad with "00". This is the same slightly
// unusual scheme the original Java used and must be preserved exactly for
// compatibility with tokens already produced by the server-side encoder.
std::string utf8ToHexCompat(const std::string& str, bool havePadding) {
    std::string result;
    size_t i = 0;
    static const char* hexDigits = "0123456789abcdef";
    while (i < str.size()) {
        unsigned char c0 = static_cast<unsigned char>(str[i]);
        int cpLen = 1;
        if ((c0 & 0x80) == 0x00) cpLen = 1;
        else if ((c0 & 0xE0) == 0xC0) cpLen = 2;
        else if ((c0 & 0xF0) == 0xE0) cpLen = 3;
        else if ((c0 & 0xF8) == 0xF0) cpLen = 4;
        if (i + cpLen > str.size()) cpLen = 1; // malformed input guard

        std::string hex;
        for (int k = 0; k < cpLen; k++) {
            unsigned char b = static_cast<unsigned char>(str[i + k]);
            hex.push_back(hexDigits[(b >> 4) & 0xF]);
            hex.push_back(hexDigits[b & 0xF]);
        }
        if (havePadding && hex.size() == 2) {
            hex = "00" + hex;
        }
        result += hex;
        i += cpLen;
    }
    return result;
}

bool pbkdf2DeriveKey(const std::string& password, const std::string& salt,
                      int iterations, size_t outLen, std::vector<uint8_t>* out) {
    const mbedtls_md_info_t* mdInfo = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!mdInfo) return false;

    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    if (mbedtls_md_setup(&ctx, mdInfo, 1) != 0) {
        mbedtls_md_free(&ctx);
        return false;
    }

    out->resize(outLen);
    int rc = mbedtls_pkcs5_pbkdf2_hmac(
        &ctx,
        reinterpret_cast<const uint8_t*>(password.data()), password.size(),
        reinterpret_cast<const uint8_t*>(salt.data()), salt.size(),
        static_cast<unsigned int>(iterations),
        static_cast<uint32_t>(outLen), out->data());

    mbedtls_md_free(&ctx);
    return rc == 0;
}

bool hmacSha256(const uint8_t* key, size_t keyLen, const uint8_t* data, size_t dataLen,
                 uint8_t out[32]) {
    const mbedtls_md_info_t* mdInfo = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!mdInfo) return false;
    return mbedtls_md_hmac(mdInfo, key, keyLen, data, dataLen, out) == 0;
}

bool aesCbcDecryptPkcs7(const uint8_t* key16, const uint8_t* iv16,
                         const uint8_t* ciphertext, size_t ciphertextLen,
                         std::vector<uint8_t>* plaintext) {
    if (ciphertextLen == 0 || ciphertextLen % 16 != 0) return false;

    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    if (mbedtls_aes_setkey_dec(&aes, key16, 128) != 0) {
        mbedtls_aes_free(&aes);
        return false;
    }

    std::vector<uint8_t> ivCopy(iv16, iv16 + 16);
    plaintext->resize(ciphertextLen);
    int rc = mbedtls_aes_crypt_cbc(&aes, MBEDTLS_AES_DECRYPT, ciphertextLen,
                                    ivCopy.data(), ciphertext, plaintext->data());
    mbedtls_aes_free(&aes);
    if (rc != 0) return false;

    // Strip PKCS7 padding.
    uint8_t pad = plaintext->back();
    if (pad == 0 || pad > 16 || pad > plaintext->size()) return false;
    for (size_t i = 0; i < pad; i++) {
        if ((*plaintext)[plaintext->size() - 1 - i] != pad) return false;
    }
    plaintext->resize(plaintext->size() - pad);
    return true;
}

// --- decryptLic -------------------------------------------------------
// Direct native port of FlutterFoxitpdfPlugin#decryptLic. Behavior and
// wire format are unchanged from the original Java implementation.
std::string decryptLicImpl(const std::string& encryptionKey, const std::string& encryptedToken) {
    if (encryptionKey.size() != 32 || encryptedToken.empty()) return {};

    std::vector<uint8_t> derived;
    if (!pbkdf2DeriveKey(encryptionKey, kHardCodedSalt, kIterations, kDerivedKeyLenBytes, &derived)) {
        return {};
    }
    const uint8_t* signingKey = derived.data();       // bytes [0,16)
    const uint8_t* aesKey = derived.data() + 16;       // bytes [16,32)

    std::vector<uint8_t> outer;
    if (!base64UrlDecode(encryptedToken, &outer)) { secureZero(derived.data(), derived.size()); return {}; }
    std::string outerStr(outer.begin(), outer.end());
    std::vector<uint8_t> token;
    if (!base64UrlDecode(outerStr, &token)) { secureZero(derived.data(), derived.size()); return {}; }

    if (token.size() < 57) { secureZero(derived.data(), derived.size()); return {}; }

    const uint8_t* iv = token.data() + 9;                       // [9,25)
    const uint8_t* ciphertext = token.data() + 25;               // [25, len-32)
    size_t ciphertextLen = token.size() - 32 - 25;
    const uint8_t* hmacTag = token.data() + (token.size() - 32); // last 32 bytes

    uint8_t computedTag[32];
    if (!hmacSha256(signingKey, 16, token.data(), token.size() - 32, computedTag)) {
        secureZero(derived.data(), derived.size());
        return {};
    }
    if (!constantTimeEqual(computedTag, hmacTag, 32)) {
        secureZero(derived.data(), derived.size());
        return {};
    }

    std::vector<uint8_t> plaintext;
    bool ok = aesCbcDecryptPkcs7(aesKey, iv, ciphertext, ciphertextLen, &plaintext);
    secureZero(derived.data(), derived.size());
    if (!ok) return {};

    return std::string(plaintext.begin(), plaintext.end());
}

// --- ObfuscationUtil.decrypt -------------------------------------------
// Direct native port. The original Java derived the Cipher transformation
// name ("AES/CBC/PKCS5Padding") and key algorithm name ("AES") at runtime
// via an XOR trick keyed on bookId (ObfuscationUtil#getOrgPs /
// #getXorPs). That XOR trick had a bug: the bookId-derived digits (`mdf`)
// were computed but never actually mixed into the output, so the resolved
// algorithm name was constant regardless of bookId. Native code has no
// need for that string-reconstruction trick at all -- the algorithm is
// simply AES-128-CBC/PKCS7 directly, hardcoded below. This preserves the
// exact current runtime behavior (and therefore compatibility with
// already-issued tokens) while removing the pointless, buggy, and
// decompile-visible obfuscation layer entirely.
std::string obfuscationDecryptImpl(const std::string& encrypted, const std::string& key, int /*bookId*/) {
    if (encrypted.empty() || key.size() < 4) return {};

    std::string keyHex = utf8ToHexCompat(key, false);
    std::string ivHex = utf8ToHexCompat(key.substr(0, 4), true);

    if (keyHex.size() < 32 || ivHex.size() < 32) return {}; // need 16 bytes each once parsed as raw ASCII

    // The original Java used the *hex string's own UTF-8 bytes* as the raw
    // AES key material (not the decoded hex value) -- i.e. SecretKeySpec
    // was built from keyHex.getBytes(UTF_8). Replicate exactly: use the
    // first 16 bytes of the ASCII hex string as the AES-128 key/IV.
    if (keyHex.size() < 16 || ivHex.size() < 16) return {};

    std::vector<uint8_t> keyBytes(keyHex.begin(), keyHex.begin() + 16);
    std::vector<uint8_t> ivBytes(ivHex.begin(), ivHex.begin() + 16);

    std::vector<uint8_t> raw;
    // Standard (non-URL-safe) Base64.DEFAULT decoding, as in the original.
    {
        size_t decodedLen = 0;
        int rc = mbedtls_base64_decode(nullptr, 0, &decodedLen,
                                        reinterpret_cast<const uint8_t*>(encrypted.data()),
                                        encrypted.size());
        if (rc != 0 && rc != MBEDTLS_ERR_BASE64_BUFFER_TOO_SMALL) return {};
        raw.resize(decodedLen);
        size_t written = 0;
        rc = mbedtls_base64_decode(raw.data(), raw.size(), &written,
                                    reinterpret_cast<const uint8_t*>(encrypted.data()),
                                    encrypted.size());
        if (rc != 0) return {};
        raw.resize(written);
    }

    std::vector<uint8_t> plaintext;
    if (!aesCbcDecryptPkcs7(keyBytes.data(), ivBytes.data(), raw.data(), raw.size(), &plaintext)) {
        return {};
    }
    return std::string(plaintext.begin(), plaintext.end());
}

// --- JNI entry points ---------------------------------------------------

jstring Foxit_decryptLic(JNIEnv* env, jclass, jstring encryptionKey, jstring encryptedToken) {
    std::string key = jstringToUtf8(env, encryptionKey);
    std::string token = jstringToUtf8(env, encryptedToken);
    std::string result = decryptLicImpl(key, token);
    secureZero(&key[0], key.size());
    if (result.empty()) return nullptr;
    jstring jresult = utf8ToJstring(env, result);
    secureZero(&result[0], result.size());
    return jresult;
}

jstring Foxit_obfuscationDecrypt(JNIEnv* env, jclass, jstring encrypted, jstring key, jint bookId) {
    std::string encStr = jstringToUtf8(env, encrypted);
    std::string keyStr = jstringToUtf8(env, key);
    std::string result = obfuscationDecryptImpl(encStr, keyStr, bookId);
    secureZero(&keyStr[0], keyStr.size());
    if (result.empty()) return nullptr;
    return utf8ToJstring(env, result);
}

JNINativeMethod gMethods[] = {
    {const_cast<char*>("nativeDecryptLic"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(Foxit_decryptLic)},
    {const_cast<char*>("nativeObfuscationDecrypt"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;"),
     reinterpret_cast<void*>(Foxit_obfuscationDecrypt)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/foxit/flutterfoxitpdf/NativeCrypto");
    if (!clazz) return JNI_ERR;

    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) != 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
