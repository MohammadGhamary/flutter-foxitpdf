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
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;


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

    Log.d(TAG, "ggggggggggggggggggggggggggggggggggg");

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
      Log.d(TAG, "sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss");

      String sn = decryptLic(bookTranslatorE, decryptLic(bookTranslatorE, bookAuthorS));
      String key = decryptLic(bookTranslatorE, bookPublisherK);

      Log.d(TAG, "Decrypted SN: " + sn);
      Log.d(TAG, "Decrypted Key: " + key);

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
   * Decrypts a license fragment (SN or key) using AES-CBC with an
   * HMAC-verified, PBKDF2-derived key. Moved here from PDFReaderActivity so
   * the license can be decrypted and the library initialized before the
   * Activity (and its PDFViewCtrl/UIExtensionsManager) is ever created.
   */
  private String decryptLic(String encryptionKey, String encryptedToken) throws Exception {
    if (encryptionKey == null || encryptionKey.length() != 32) {
      throw new IllegalArgumentException("Encryption key must be exactly 32 characters");
    }
    if (encryptedToken == null || encryptedToken.isEmpty()) {
      throw new IllegalArgumentException("Encrypted token cannot be empty");
    }

    final String HARD_CODED_SALT = "fb0dae6afae2a731bf1398759c4e6567";
    final int ITERATIONS = 100000;

    PBEKeySpec spec = new PBEKeySpec(
            encryptionKey.toCharArray(),
            HARD_CODED_SALT.getBytes(StandardCharsets.UTF_8),
            ITERATIONS,
            256
    );
    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    byte[] derivedKey = factory.generateSecret(spec).getEncoded();

    byte[] signingKey = Arrays.copyOfRange(derivedKey, 0, 16);
    byte[] aesEncryptionKey = Arrays.copyOfRange(derivedKey, 16, 32);

    byte[] decodedOuter = Base64.decode(encryptedToken, Base64.URL_SAFE | Base64.NO_WRAP);
    String outerStr = new String(decodedOuter, StandardCharsets.UTF_8);
    byte[] token = Base64.decode(outerStr, Base64.URL_SAFE | Base64.NO_WRAP);

    if (token.length < 57) {
      throw new IllegalArgumentException("Invalid token length");
    }

    byte[] iv = Arrays.copyOfRange(token, 9, 25);
    byte[] ciphertext = Arrays.copyOfRange(token, 25, token.length - 32);
    byte[] hmacTag = Arrays.copyOfRange(token, token.length - 32, token.length);

    Mac mac = Mac.getInstance("HmacSHA256");
    SecretKeySpec macKeySpec = new SecretKeySpec(signingKey, "HmacSHA256");
    mac.init(macKeySpec);
    mac.update(token, 0, token.length - 32);
    byte[] computedHmac = mac.doFinal();

    if (!MessageDigest.isEqual(computedHmac, hmacTag)) {
      throw new SecurityException("HMAC verification failed");
    }

    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    SecretKeySpec keySpec = new SecretKeySpec(aesEncryptionKey, "AES");
    IvParameterSpec ivSpec = new IvParameterSpec(iv);

    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
    byte[] decryptedBytes = cipher.doFinal(ciphertext);

    return new String(decryptedBytes, StandardCharsets.UTF_8);
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