package com.foxit.flutterfoxitpdf;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.foxit.sdk.PDFViewCtrl;
import com.foxit.uiextensions.UIExtensionsManager;
import com.foxit.uiextensions.utils.ActManager;
import com.foxit.uiextensions.utils.AppFileUtil;
import com.foxit.uiextensions.utils.AppStorageManager;
import com.foxit.uiextensions.utils.SystemUiHelper;
import com.foxit.uiextensions.utils.UIToast;
import com.foxit.uiextensions.config.Config;

import com.foxit.uiextensions.controls.propertybar.IViewSettingsWindow;
import com.foxit.uiextensions.controls.toolbar.ToolbarItemConfig;
import com.foxit.uiextensions.controls.toolbar.BaseBar;
import com.foxit.uiextensions.controls.toolbar.IBarsHandler;

public class PDFReaderActivity extends FragmentActivity {
    private static final String TAG = "PDFReaderActivity";
    public static final int REQUEST_OPEN_DOCUMENT_TREE = 0xF001;
    public static final int REQUEST_SELECT_DEFAULT_FOLDER = 0xF002;

    public static final int REQUEST_EXTERNAL_STORAGE_MANAGER = 111;
    public static final int REQUEST_EXTERNAL_STORAGE = 222;

    private static final String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public PDFViewCtrl pdfViewCtrl;
    private UIExtensionsManager uiextensionsManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActManager.getInstance().setCurrentActivity(this);
        SystemUiHelper.getInstance().setStatusBarColor(getWindow(), ContextCompat.getColor(this, com.foxit.uiextensions.R.color.ui_color_top_bar_main));

        AppStorageManager.setOpenTreeRequestCode(REQUEST_OPEN_DOCUMENT_TREE);

        pdfViewCtrl = new PDFViewCtrl(getApplicationContext());
        pdfViewCtrl.setPageBinding(PDFViewCtrl.RIGHT_EDGE);

        Bundle bundle = getIntent().getExtras();
        String configJson = bundle != null ? bundle.getString("configurations") : null;

        if (configJson != null && !configJson.isEmpty()) {
            InputStream stream = new ByteArrayInputStream(configJson.getBytes(StandardCharsets.UTF_8));
            Config config = new Config(stream);
            uiextensionsManager = new UIExtensionsManager(this, pdfViewCtrl, config);
        } else {
            uiextensionsManager = new UIExtensionsManager(this, pdfViewCtrl, null);
        }

        if (uiextensionsManager.getSettingWindow() != null) {
            uiextensionsManager.getSettingWindow().setVisible(IViewSettingsWindow.TYPE_REFLOW, false);
        }
        if (uiextensionsManager.getMainFrame() != null) {
            uiextensionsManager.getMainFrame().removeTab(ToolbarItemConfig.ITEM_FORM_TAB);
            uiextensionsManager.getMainFrame().removeTab(ToolbarItemConfig.ITEM_FILLSIGN_TAB);
        }
        if (uiextensionsManager.getBarManager() != null) {
            uiextensionsManager.getBarManager().removeItem(IBarsHandler.BarName.TOP_BAR, BaseBar.TB_Position.Position_RB, 1);
        }
        uiextensionsManager.setAutoSaveDoc(true);

        uiextensionsManager.setAttachedActivity(this);
        pdfViewCtrl.setUIExtensionsManager(uiextensionsManager);
        pdfViewCtrl.setAttachedActivity(this);
        uiextensionsManager.onCreate(this, pdfViewCtrl, null);

        if (Build.VERSION.SDK_INT >= 30 && !AppFileUtil.isExternalStorageLegacy()) {
            AppStorageManager storageManager = AppStorageManager.getInstance(this);
            boolean needPermission = storageManager.needManageExternalStoragePermission();
            if (!AppStorageManager.isExternalStorageManager() && needPermission) {
                storageManager.requestExternalStorageManager(this, REQUEST_EXTERNAL_STORAGE_MANAGER);
            } else if (!needPermission) {
                checkStorageState();
            } else {
                openDocument();
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            checkStorageState();
        } else {
            openDocument();
        }

        setContentView(uiextensionsManager.getContentView());
    }

    private void checkStorageState() {
        int permission = ContextCompat.checkSelfPermission(this.getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
        } else {
            selectDefaultFolderOrNot();
        }
    }

    private void selectDefaultFolderOrNot() {
        if (AppFileUtil.needScopedStorageAdaptation()) {
            if (TextUtils.isEmpty(AppStorageManager.getInstance(this).getDefaultFolder())) {
                AppFileUtil.checkCallDocumentTreeUriPermission(this, REQUEST_SELECT_DEFAULT_FOLDER,
                        Uri.parse(AppFileUtil.getExternalRootDocumentTreeUriPath()));
                UIToast.getInstance(getApplicationContext()).show("Please select the default folder, you can create one if it does not exist.");
            } else {
                openDocument();
            }
        } else {
            openDocument();
        }
    }

    private void openDocument() {
        Bundle bundle = getIntent().getExtras();

        if (bundle == null) {
            Log.e(TAG, "Intent extras (Bundle) cannot be null!");
            finish();
            return;
        }

        String path = bundle.getString("path", "");
        int bookId = bundle.getInt("bookId", 0);
        String bookCategory = bundle.getString("bookCategory", "");

        String bookTitle = bundle.getString("bookTitle", "");
        String bookName = bundle.getString("bookName", "");

        // License decryption + Library.initialize() now happen in
        // FlutterFoxitpdfPlugin.openDocument(), before this Activity is
        // started, since PDFViewCtrl/UIExtensionsManager (created in
        // onCreate) require the library to already be initialized.

        String decryptedObfuscatorKey = ObfuscationUtil.decrypt(bookName, bookTitle, bookId);
        if (decryptedObfuscatorKey == null) {
            decryptedObfuscatorKey = "";
        }

        PositionObfuscator obfuscator = new PositionObfuscator(decryptedObfuscatorKey, true);

        String decryptedCategory = ObfuscationUtil.decrypt(bookCategory, bookTitle, bookId);
        byte[] finalPassword = null;

        if (decryptedCategory != null) {
            String deobfuscatedString = obfuscator.deobfuscate(decryptedCategory);
            if (deobfuscatedString != null) {
                finalPassword = deobfuscatedString.getBytes(StandardCharsets.UTF_8);
            }
        }

        int type = bundle.getInt("type", 0);
        if (type == 0) {
            uiextensionsManager.openDocument(path, finalPassword);
        } else {
            pdfViewCtrl.openDocFromUrl(path, finalPassword, null, null);
        }
    }

    public static class PositionObfuscator {
        private final String key;
        private final boolean base64EncodeOutput;

        public PositionObfuscator(String key, boolean base64EncodeOutput) {
            this.key = key != null ? key : "";
            this.base64EncodeOutput = base64EncodeOutput;
        }

        private int seedFromKeyAndLength(String key, int length) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));

                int seed = ((hash[0] & 0xFF) << 24)
                        | ((hash[1] & 0xFF) << 16)
                        | ((hash[2] & 0xFF) << 8)
                        | (hash[3] & 0xFF);

                seed ^= (length * 0x9e3779b1);
                return seed;
            } catch (Exception e) {
                throw new RuntimeException("SHA-256 algorithm not found", e);
            }
        }

        private int lcgNext(int state) {
            return state * 1664525 + 1013904223;
        }

        private int[] permutation(int length) {
            int[] perm = new int[length];
            for (int i = 0; i < length; i++) {
                perm[i] = i;
            }

            if (length <= 1) {
                return perm;
            }

            int state = seedFromKeyAndLength(this.key, length);

            for (int i = length - 1; i >= 1; i--) {
                state = lcgNext(state);
                int j = (int) (((long) (state >>> 1)) % (i + 1));

                int temp = perm[i];
                perm[i] = perm[j];
                perm[j] = temp;
            }

            return perm;
        }

        private int[] toCodePoints(String input) {
            return input.codePoints().toArray();
        }

        private String fromCodePoints(int[] cps) {
            return new String(cps, 0, cps.length);
        }

        public String deobfuscate(String obfuscated) {
            if (obfuscated == null || obfuscated.isEmpty()) {
                return obfuscated;
            }

            String decoded;
            if (this.base64EncodeOutput) {
                try {
                    byte[] bytes = Base64.decode(obfuscated, Base64.DEFAULT);
                    decoded = new String(bytes, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    decoded = obfuscated;
                }
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                selectDefaultFolderOrNot();
            } else {
                UIToast.getInstance(getApplicationContext()).show("Permission Denied");
            }
        } else {
            if (uiextensionsManager != null) {
                uiextensionsManager.handleRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (uiextensionsManager == null) return;
        uiextensionsManager.onStart(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (uiextensionsManager == null) return;
        uiextensionsManager.onPause(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uiextensionsManager == null) return;
        uiextensionsManager.onResume(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (uiextensionsManager == null) return;
        uiextensionsManager.onStop(this);
    }

    @Override
    protected void onDestroy() {
        if (uiextensionsManager != null) {
            uiextensionsManager.onDestroy(this);
        }
        super.onDestroy();
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXTERNAL_STORAGE_MANAGER) {
            AppFileUtil.updateIsExternalStorageManager();
            if (!AppFileUtil.isExternalStorageManager()) {
                checkStorageState();
            } else {
                openDocument();
            }
        } else if (requestCode == AppStorageManager.getOpenTreeRequestCode() || requestCode == REQUEST_SELECT_DEFAULT_FOLDER) {
            if (resultCode == Activity.RESULT_OK) {
                if (data == null || data.getData() == null) return;
                Uri uri = data.getData();
                int modeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, modeFlags);
                AppStorageManager storageManager = AppStorageManager.getInstance(getApplicationContext());
                if (TextUtils.isEmpty(storageManager.getDefaultFolder())) {
                    String defaultPath = AppFileUtil.toPathFromDocumentTreeUri(uri);
                    storageManager.setDefaultFolder(defaultPath);
                    openDocument();
                }
            } else {
                UIToast.getInstance(getApplicationContext()).show("Permission Denied");
                finish();
            }
        }
        if (uiextensionsManager != null) {
            uiextensionsManager.handleActivityResult(this, requestCode, resultCode, data);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (uiextensionsManager == null) return;
        uiextensionsManager.onConfigurationChanged(this, newConfig);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (uiextensionsManager != null && uiextensionsManager.onKeyDown(this, keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}