package com.foxit.flutterfoxitpdf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import java.util.HashMap;

import org.json.JSONObject;

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


public class FlutterFoxitpdfPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
  private static final String TAG = "FlutterFoxitpdfPlugin";

  private MethodChannel channel;
  private Activity activity;
  private int errorCode = Constants.e_ErrUnknown;

  // FIX: kept as a field so we can unregister it before registering a new
  // one. Previously a brand-new anonymous listener was registered on every
  // onAttachedToActivity/onReattachedToActivityForConfigChanges call
  // without ever unregistering the old one -- each config change (e.g.
  // rotation) leaked one more listener, and closing PDFReaderActivity
  // would eventually fire "documentClosed" to Dart multiple times for a
  // single close event.
  private Application.ActivityLifecycleCallbacks lifecycleCallbacks;

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
    unregisterActivityLifecycleCallbacks();
    activity = null;
  }

  @Override
  public void onDetachedFromActivity() {
    unregisterActivityLifecycleCallbacks();
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

    if (path == null || path.trim().length() < 1) {
      result.error("" + Constants.e_ErrParam,"Invalid path", Constants.e_ErrParam);
      return;
    }

    if (activity == null) {
      result.error("-1","The Activity is null", -1);
      return;
    }

    int resolvedBookId = bookId != null ? bookId : 0;

    try {
      String translatorKey = ObfuscationUtil.decrypt(bookTranslatorE, bookTitle, resolvedBookId);
      String bookAuthorSD = ObfuscationUtil.decrypt(bookAuthorS, bookTitle, resolvedBookId);
      String bookPublisherKD = ObfuscationUtil.decrypt(bookPublisherK, bookTitle, resolvedBookId);

      if (translatorKey == null) {
        errorCode = Constants.e_ErrUnknown;
        result.error("" + errorCode, "Failed to decrypt bookTranslatorE", errorCode);
        return;
      }

      // decryptLic now runs entirely in native code (see NativeCrypto /
      // native/foxit_native_crypto.cpp). No key material, salt, or token is
      // ever logged -- do NOT reintroduce Log.d()/Log.v() around this call.
      String sn = NativeCrypto.nativeDecryptLic(translatorKey, NativeCrypto.nativeDecryptLic(translatorKey, bookAuthorSD));
      String key = NativeCrypto.nativeDecryptLic(translatorKey, bookPublisherKD);

      if (sn == null || key == null) {
        errorCode = Constants.e_ErrUnknown;
        result.error("" + errorCode, "Failed to decrypt license", errorCode);
        return;
      }

      errorCode = Library.initialize(sn, key);
    } catch (Exception e) {
      // Log the exception type only -- never e.getMessage(), which could
      // echo back fragments of the key/token in some crypto providers.
      Log.e(TAG, "Failed to decrypt license: " + e.getClass().getSimpleName());
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
    bundle.putInt("bookId", resolvedBookId);
    bundle.putString("bookTitle", bookTitle);
    bundle.putString("bookName", bookName);
    bundle.putString("bookCategory", bookCategory);
    bundle.putString("configurations", configurations.toString());
    intent.putExtras(bundle);

    activity.startActivity(intent);
    result.success(true);
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
    if (activity == null) return;

    // FIX: unregister any previously-registered listener before adding a
    // new one, so config changes don't accumulate duplicate listeners.
    unregisterActivityLifecycleCallbacks();

    lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
      @Override
      public void onActivityDestroyed(@NonNull Activity destroyedActivity) {
        if (destroyedActivity.getClass().getName().equals("com.foxit.flutterfoxitpdf.PDFReaderActivity")) {
          new Handler(Looper.getMainLooper()).post(() -> {
            if (channel != null) {
              channel.invokeMethod("documentClosed", null);
            }
          });
        }
      }
      @Override
      public void onActivityCreated(Activity createdActivity, Bundle bundle) {
        if (createdActivity.getClass().getName().equals("com.foxit.flutterfoxitpdf.PDFReaderActivity")) {
          createdActivity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                  WindowManager.LayoutParams.FLAG_SECURE);
        }
      }
      @Override
      public void onActivityStarted(Activity startedActivity) {}
      @Override
      public void onActivityResumed(Activity resumedActivity) {}
      @Override
      public void onActivityPaused(Activity pausedActivity) {}
      @Override
      public void onActivityStopped(Activity stoppedActivity) {}
      @Override
      public void onActivitySaveInstanceState(Activity savedActivity, Bundle bundle) {}
    };

    activity.getApplication().registerActivityLifecycleCallbacks(lifecycleCallbacks);
  }

  private void unregisterActivityLifecycleCallbacks() {
    if (lifecycleCallbacks != null && activity != null) {
      activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
    }
    lifecycleCallbacks = null;
  }

}