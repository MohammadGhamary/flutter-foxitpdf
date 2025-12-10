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
import com.foxit.flutterfoxitpdf.R;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.Cipher;
import android.util.Base64;
import java.nio.charset.Charset;
import java.util.List;
import java.security.MessageDigest;
import java.math.BigInteger;
import org.bouncycastle.util.encoders.Hex;
import java.util.Arrays;

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
import com.foxit.uiextensions.utils.AppTheme;
import com.foxit.uiextensions.utils.SystemUiHelper;
import com.foxit.uiextensions.utils.UIToast;
import com.foxit.uiextensions.config.Config;

import com.foxit.uiextensions.controls.propertybar.IViewSettingsWindow;
import com.foxit.uiextensions.controls.toolbar.ToolbarItemConfig;
import com.foxit.uiextensions.controls.toolbar.BaseBar;
import com.foxit.uiextensions.controls.toolbar.IBarsHandler;

public class PDFReaderActivity extends FragmentActivity {
    public static final int REQUEST_OPEN_DOCUMENT_TREE = 0xF001;
    public static final int REQUEST_SELECT_DEFAULT_FOLDER = 0xF002;

    public static final int REQUEST_EXTERNAL_STORAGE_MANAGER = 111;
    public static final int REQUEST_EXTERNAL_STORAGE = 222;

    private static final String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private static final List<Integer> byttesps = Arrays.asList(
            99, 81, 57, 65, 126, 62, 56, 43, 75, 58, 79, 127, 108, 59, 106, 122
    );

    private static final List<Integer> byttesps1 = Arrays.asList(
            73, 77, 91, 39, 75, 74, 75, 39,
            88, 67, 75, 91, 63, 88, 105, 108,
            108, 97, 102, 111
    );

    private static final List<Integer> byttesps2 = Arrays.asList(
            73, 77, 91
    );

    public PDFViewCtrl pdfViewCtrl;
    private UIExtensionsManager uiextensionsManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActManager.getInstance().setCurrentActivity(this);
        SystemUiHelper.getInstance().setStatusBarColor(getWindow(), getResources().getColor(com.foxit.uiextensions.R.color.ui_color_top_bar_main));

        AppStorageManager.setOpenTreeRequestCode(REQUEST_OPEN_DOCUMENT_TREE);

        pdfViewCtrl = new PDFViewCtrl(getApplicationContext());
        pdfViewCtrl.setPageBinding(PDFViewCtrl.RIGHT_EDGE);

        Bundle bundle = getIntent().getExtras();
        String configJson = bundle.getString("configurations");

        if (configJson != null) {
            InputStream stream = new ByteArrayInputStream(configJson.getBytes(StandardCharsets.UTF_8));
            Config config = new Config(stream);
            uiextensionsManager = new UIExtensionsManager(this, pdfViewCtrl, config);
        }else {
            uiextensionsManager = new UIExtensionsManager(this, pdfViewCtrl, null);
        }

        uiextensionsManager.getSettingWindow().setVisible(IViewSettingsWindow.TYPE_REFLOW, false);
        uiextensionsManager.getMainFrame().removeTab(ToolbarItemConfig.ITEM_FORM_TAB);
        uiextensionsManager.getMainFrame().removeTab(ToolbarItemConfig.ITEM_FILLSIGN_TAB);
        uiextensionsManager.getBarManager().removeItem(IBarsHandler.BarName.TOP_BAR, BaseBar.TB_Position.Position_RB, 1);
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
                UIToast.getInstance(getApplicationContext()).show("Please select the default folder,you can create one when it not exists.");
            } else {
                openDocument();
            }
        } else {
            openDocument();
        }
    }

    private void openDocument() {
        Bundle bundle = getIntent().getExtras();

        String path = bundle == null ? "" : bundle.getString("path");
        int bookId = bundle == null ? 0 : bundle.getInt("bookId");
        String password = bundle == null ? "" : bundle.getString("password", "");


        int type = bundle == null ? 0 : bundle.getInt("type", 0);
        if (type == 0) {
            uiextensionsManager.openDocument(path, decrypt(password, getOrgPs(bookId, byttesps), bookId).getBytes());
        } else {
            pdfViewCtrl.openDocFromUrl(path, decrypt(password, getOrgPs(bookId, byttesps), bookId).getBytes(), null, null);
        }
        
    }

    private String decrypt(String encrypted, String key, int bookId) {
        try {
            SecretKeySpec skeySpec = new SecretKeySpec(
                    utf8ToHex(key, false).getBytes(),
                    getOrgPs(bookId, byttesps2)
            );

            IvParameterSpec ivSpec = new IvParameterSpec(
                    utf8ToHex(key.substring(0, 4), true).getBytes()
            );

            Cipher ecipher = Cipher.getInstance(getOrgPs(bookId, byttesps1));
            ecipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);

            byte[] raw = Base64.decode(encrypted, Base64.DEFAULT);
            byte[] originalBytes = ecipher.doFinal(raw);

            return new String(originalBytes, StandardCharsets.UTF_8);

        } catch (Exception ignored) {
        }
        return null;
    }

    private String getOrgPs(int bookId, List<Integer> list) {
        StringBuilder ps = new StringBuilder();
        for (int i : list) {
            ps.append(getXorPs(bookId, i));
        }
        return ps.toString();
    }

    private String getXorPs(int bookId, int value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(Integer.toString(bookId).getBytes());
            String mdf = new BigInteger(1, digest).toString(4);

            // pad to length 4 just like Kotlin
            while (mdf.length() < 4) {
                mdf = "0" + mdf;
            }

            // original Kotlin logic: only returns XOR char
            return new String(Character.toChars(value ^ 8));

        } catch (Exception e) {
            return "";
        }
    }

    private String utf8ToHex(String str, boolean havePadding) {
        StringBuilder hexResult = new StringBuilder();

        for (String ch : str.split("")) {
            if (ch.isEmpty()) continue;

            byte[] utf8 = ch.getBytes(StandardCharsets.UTF_8);
            byte[] hexBytes = Hex.encode(utf8);   // BouncyCastle Hex encoder

            String res = new String(hexBytes);
            if (res.length() == 2 && havePadding) {
                res = "00" + res;
            }

            hexResult.append(res);
        }
        return hexResult.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_EXTERNAL_STORAGE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
            freeMemory();
        }
        super.onDestroy();
    }

    private void freeMemory() {
        System.runFinalization();
        Runtime.getRuntime().gc();
        System.gc();
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
        if (uiextensionsManager != null)
            uiextensionsManager.handleActivityResult(this, requestCode, resultCode, data);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (uiextensionsManager == null) return;
        uiextensionsManager.onConfigurationChanged(this, newConfig);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (uiextensionsManager != null && uiextensionsManager.onKeyDown(this, keyCode, event))
            return true;
        return super.onKeyDown(keyCode, event);
    }

}
