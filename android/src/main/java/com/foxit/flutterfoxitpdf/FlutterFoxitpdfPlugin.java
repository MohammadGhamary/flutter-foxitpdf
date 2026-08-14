package com.foxit.flutterfoxitpdf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.json.JSONException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;
import android.util.Log;

import com.foxit.sdk.common.Constants;
import com.foxit.sdk.common.Library;

import androidx.annotation.NonNull;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.MethodCall;


/** FlutterFoxitpdfPlugin */
public class FlutterFoxitpdfPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
  private static final String TAG = "FlutterFoxitpdfPlugin";

  private MethodChannel channel;
  private Activity activity;
  private int errorCode = Constants.e_ErrUnknown;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPlugin.FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_foxitpdf");
    channel.setMethodCallHandler(this);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    if (channel != null) {
      channel.setMethodCallHandler(null);
      channel = null;
    }
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
    registerActivityLifecycleCallbacks();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
    registerActivityLifecycleCallbacks();
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    activity = null;
  }

  @Override
  public void onDetachedFromActivity() {
    activity = null;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    switch (call.method) {
      case "getPlatformVersion":
        result.success("Android " + android.os.Build.VERSION.RELEASE);
        break;
      case "initialize":
        initialize(call, result);
        break;
      case "openDocument":
        openDocument(call, result);
        break;
      case "openDocFromUrl":
        openDocFromUrl(call, result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private void initialize(MethodCall call, Result result) {
    String sn = call.argument("sn");
    String key = call.argument("key");

    errorCode = Library.initialize(sn, key);
    result.success(errorCode);
  }

  private void openDocument(MethodCall call, Result result) {

    String path = call.argument("path");
    String password = call.argument("password");
    Integer bookId = call.argument("bookId");
    String bookTitle = call.argument("bookTitle");
    String bookCategory = call.argument("bookCategory");
    String bookName = call.argument("bookName");
    String bookAuthorS = call.argument("bookAuthorS");
    String bookPublisherK = call.argument("bookPublisherK");
    String bookTranslatorE = call.argument("bookTranslatorE");
    HashMap<String, Object> configurationsMap = call.argument("configurations");

    Log.d(TAG, "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy");

    if (path == null || path.trim().length() < 1) {
      result.error("" + Constants.e_ErrParam,"Invalid path", Constants.e_ErrParam);
      return;
    }

    if (activity == null) {
      result.error("-1","The Activity is null", -1);
      return;
    }

    // The license must be decrypted and the Foxit library initialized here,
    // BEFORE PDFReaderActivity is created: its onCreate() already constructs
    // PDFViewCtrl/UIExtensionsManager, which require the library to be
    // initialized beforehand. Doing this inside the Activity is too late.
    try {
      // bookTranslatorE is itself an encrypted string — the same
      // obfuscation scheme PDFReaderActivity uses for bookName/bookCategory
      // (keyed by bookTitle + bookId). It must be decrypted first; the
      // *result* is the real key used to decrypt bookAuthorS/bookPublisherK
      // below. Do not pass the raw bookTranslatorE into decryptLic().
      String translatorKey = ObfuscationUtil.decrypt(bookTranslatorE, bookTitle, bookId != null ? bookId : 0);
      String bookAuthorSD = ObfuscationUtil.decrypt(bookAuthorS, bookTitle, bookId != null ? bookId : 0);
      String bookPublisherKD = ObfuscationUtil.decrypt(bookPublisherK, bookTitle, bookId != null ? bookId : 0);

      String bookNameD = ObfuscationUtil.decrypt(bookName, bookTitle, bookId != null ? bookId : 0);
      String bookCategoryD = ObfuscationUtil.decrypt(bookCategory, bookTitle, bookId != null ? bookId : 0);

      Log.d(TAG, "translatorKey: " + translatorKey);
      Log.d(TAG, "bookAuthorSD: " + bookAuthorSD);
      Log.d(TAG, "bookName: " + bookName);
      Log.d(TAG, "bookNameD: " + bookNameD);
      Log.d(TAG, "bookCategoryD: " + bookCategoryD);

      if (translatorKey == null) {
        errorCode = Constants.e_ErrUnknown;
        result.error("" + errorCode, "Failed to decrypt bookTranslatorE", errorCode);
        return;
      }

      String sn = decryptLic(translatorKey, decryptLic(translatorKey, bookAuthorSD));
      String key = decryptLic(translatorKey, bookPublisherKD);

      Log.d(TAG, "Decrypted SN: " + sn);
      Log.d(TAG, "Decrypted Key: " + key);

      // Do NOT log sn/key/translatorKey here, even at debug level: they are
      // the raw license credentials and must never end up in logcat.
      errorCode = Library.initialize(sn, key);
    } catch (Exception e) {
      Log.e(TAG, "Failed to decrypt license", e);
      errorCode = Constants.e_ErrUnknown;
      result.error("" + errorCode, "Failed to decrypt license", errorCode);
      return;
    }

    if (errorCode != Constants.e_ErrSuccess) {
      result.error("" + errorCode,"Failed to initialize Foxit Library", errorCode);
      return;
    }

    JSONObject configurations = new JSONObject(configurationsMap != null ? configurationsMap : new HashMap<>());

    Intent intent = new Intent(activity, PDFReaderActivity.class);
    Bundle bundle = new Bundle();
    bundle.putInt("type", 0);
    bundle.putString("path", path);
    bundle.putString("password", password);
    bundle.putInt("bookId", bookId != null ? bookId : 0);
    bundle.putString("bookTitle", bookTitle);
    bundle.putString("bookName", bookName);
    bundle.putString("bookCategory", bookCategory);
    bundle.putString("configurations", configurations.toString());
    // Note: bookAuthorS / bookPublisherK / bookTranslatorE are no longer passed on —
    // the library is already initialized by this point, and the Activity no
    // longer needs the raw encrypted license material.
    intent.putExtras(bundle);

    activity.startActivity(intent);
    result.success(true);
  }

  /**
   * Decrypts a license fragment (SN or key) using AES-128-CBC with an
   * HMAC-SHA256-verified, PBKDF2-derived key. Moved here from
   * PDFReaderActivity so the license can be decrypted and the library
   * initialized before the Activity (and its PDFViewCtrl/UIExtensionsManager)
   * is ever created.
   *
   * Token layout after the double base64url decode:
   *   [0..9)            header / unused prefix
   *   [9..25)            16-byte AES IV
   *   [25..len-32)        ciphertext
   *   [len-32..len)       32-byte HMAC-SHA256 tag over token[0..len-32)
   */
  private String decryptLic(String encryptionKey, String encryptedToken) throws Exception {

    Log.d(TAG, "encryptionKey: " + encryptionKey);
    Log.d(TAG, "encryptedToken: " + encryptedToken);

    if (encryptionKey == null || encryptionKey.length() != 32) {
      throw new IllegalArgumentException("Encryption key must be exactly 32 characters");
    }
    if (encryptedToken == null || encryptedToken.isEmpty()) {
      throw new IllegalArgumentException("Encrypted token cannot be empty");
    }

    final String HARD_CODED_SALT = "fb0dae6afae2a731bf1398759c4e6567";
    final int ITERATIONS = 100_000;
    final int DERIVED_KEY_LEN_BITS = 32 * 8; // 32 bytes total: 16 (signing) + 16 (AES)

    // 1. Derive key material with PBKDF2-HMAC-SHA256
    SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    PBEKeySpec spec = new PBEKeySpec(
            encryptionKey.toCharArray(),
            HARD_CODED_SALT.getBytes(StandardCharsets.UTF_8),
            ITERATIONS,
            DERIVED_KEY_LEN_BITS
    );
    byte[] derivedKey = skf.generateSecret(spec).getEncoded();

    byte[] signingKey = Arrays.copyOfRange(derivedKey, 0, 16);
    byte[] aesKey = Arrays.copyOfRange(derivedKey, 16, 32);

    // 2. Decode base64url twice (outer, then inner)
    byte[] decodedOuter = Base64.decode(encryptedToken, Base64.URL_SAFE | Base64.NO_WRAP);
    byte[] token = Base64.decode(decodedOuter, Base64.URL_SAFE | Base64.NO_WRAP);

    if (token.length < 57) {
      throw new IllegalArgumentException("Invalid token length");
    }

    byte[] iv = Arrays.copyOfRange(token, 9, 25);
    byte[] ciphertext = Arrays.copyOfRange(token, 25, token.length - 32);
    byte[] hmacTag = Arrays.copyOfRange(token, token.length - 32, token.length);

    // 3. Verify HMAC-SHA256 over everything except the tag itself
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
    mac.update(token, 0, token.length - 32);
    byte[] computedTag = mac.doFinal();

    if (!MessageDigest.isEqual(computedTag, hmacTag)) {
      throw new SecurityException("HMAC verification failed");
    }

    // 4. AES-128-CBC decrypt with PKCS7 padding (Java calls it PKCS5Padding
    // for historical reasons, but for AES's 16-byte block it is PKCS7)
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE,
            new SecretKeySpec(aesKey, "AES"),
            new IvParameterSpec(iv));
    byte[] decrypted = cipher.doFinal(ciphertext);

    return new String(decrypted, StandardCharsets.UTF_8);
  }

  private void openDocFromUrl(MethodCall call, Result result) {
    if (errorCode != Constants.e_ErrSuccess) {
      result.error("" + errorCode,"Failed to initialize Foxit Library", errorCode);
      return;
    }
    String path = call.argument("path");
    String password = call.argument("password");

    if (path == null || path.trim().length() < 1) {
      result.error("" + Constants.e_ErrParam,"Invalid path", Constants.e_ErrParam);
      return;
    }

    if (activity == null) {
      result.error("-1","The Activity is null", -1);
      return;
    }

    Intent intent = new Intent(activity, PDFReaderActivity.class);
    Bundle bundle = new Bundle();
    bundle.putInt("type", 1);
    bundle.putString("path", path);
    bundle.putString("password", password);
    intent.putExtras(bundle);

    activity.startActivity(intent);
    result.success(true);
  }

  private void registerActivityLifecycleCallbacks() {
    activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
      @Override
      public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity.getClass().getName().equals("com.foxit.flutterfoxitpdf.PDFReaderActivity")) {
          new Handler(Looper.getMainLooper()).post(() -> {
            channel.invokeMethod("documentClosed", null);
          });

        }
      }
      @Override
      public void onActivityCreated(Activity activity, Bundle bundle) {
        if (activity.getClass().getName().equals("com.foxit.flutterfoxitpdf.PDFReaderActivity")) {
          activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                  WindowManager.LayoutParams.FLAG_SECURE);
        }
      }
      @Override
      public void onActivityStarted(Activity activity) {}
      @Override
      public void onActivityResumed(Activity activity) {}
      @Override
      public void onActivityPaused(Activity activity) {}
      @Override
      public void onActivityStopped(Activity activity) {}
      @Override
      public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {}
    });
  }

}