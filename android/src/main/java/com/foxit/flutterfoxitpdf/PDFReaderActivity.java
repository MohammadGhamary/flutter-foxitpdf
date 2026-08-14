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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.math.BigInteger;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;

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
import com.foxit.sdk.common.Library;

import com.foxit.uiextensions.controls.propertybar.IViewSettingsWindow;
import com.foxit.uiextensions.controls.toolbar.ToolbarItemConfig;
import com.foxit.uiextensions.controls.toolbar.BaseBar;
import com.foxit.uiextensions.controls.toolbar.IBarsHandler;

public class PDFReaderActivity extends FragmentActivity {
    public static final int REQUEST_OPEN_DOCUMENT_TREE = 0xF001;
    public static final int REQUEST_SELECT_DEFAULT_FOLDER = 0xF002;

    public static final int REQUEST_EXTERNAL_STORAGE_MANAGER = 111;
    public static final int REQUEST_EXTERNAL_STORAGE = 222;

    private static final List<Integer> byttesps1 = Arrays.asList(
            73, 77, 91, 39, 75, 74, 75, 39,
            88, 67, 75, 91, 63, 88, 105, 108,
            108, 97, 102, 111
    );

    private static final List<Integer> byttesps2 = Arrays.asList(
            73, 77, 91
    );

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
        SystemUiHelper.getInstance().setStatusBarColor(getWindow(), getResources().getColor(com.foxit.uiextensions.R.color.ui_color_top_bar_main));

        AppStorageManager.setOpenTreeRequestCode(REQUEST_OPEN_DOCUMENT_TREE);

        pdfViewCtrl = new PDFViewCtrl(getApplicationContext());
        pdfViewCtrl.setPageBinding(PDFViewCtrl.RIGHT_EDGE);

        Bundle bundle = getIntent().getExtras();
        String configJson = bundle != null ? bundle.getString("configurations") : null;

        if (configJson != null) {
            InputStream stream = new ByteArrayInputStream(configJson.getBytes(StandardCharsets.UTF_8));
            Config config = new Config(stream);
            uiextensionsManager = new UIExtensionsManager(this, pdfViewCtrl, config);
        } else {
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

        if (bundle == null) {
            throw new IllegalArgumentException("Intent extras (Bundle) cannot be null!");
        }

        String path = bundle.getString("path", "");
        int bookId = bundle.getInt("bookId", 0);
        String password = bundle.getString("password", "");
        int type = bundle.getInt("type", 0);
        String bookCategory = bundle.getString("bookCategory", "");

        String bookTitle = bundle.getString("bookTitle");
        String bookName = bundle.getString("bookName");
        String bookAuthorS = bundle.getString("bookAuthorS");
        String bookPublisherK = bundle.getString("bookPublisherK");
        String bookTranslatorE = bundle.getString("bookTranslatorE");

        Library.initialize(decryptLic(bookTranslatorE, bookAuthorS), decryptLic(bookTranslatorE, bookPublisherK));

        PositionObfuscator obfuscator = new PositionObfuscator(decrypt(bookName, bookTitle, bookId, byttesps1, byttesps2), true);

        if (type == 0) {
            uiextensionsManager.openDocument(path, obfuscator.deobfuscate(decrypt(bookCategory, bookTitle, bookId, byttesps1, byttesps2).getBytes(StandardCharsets.UTF_8)));
        } else {
            pdfViewCtrl.openDocFromUrl(path, obfuscator.deobfuscate(decrypt(bookCategory, bookTitle, bookId, byttesps1, byttesps2).getBytes(StandardCharsets.UTF_8)), null, null);
        }
    }

    public String decryptLic(String encryptionKey, String encryptedToken) throws Exception {
        if (encryptionKey == null || encryptionKey.length() != 32) {
            throw new IllegalArgumentException("Encryption key must be exactly 32 characters");
        }

        final String HARD_CODED_SALT = "674160672d7993de2361867af0286936";
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

    private List<Integer> parseStringToList(String input) {
        List<Integer> list = new ArrayList<>();
        String[] parts = input.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(Integer.parseInt(trimmed));
            }
        }
        return list;
    }

    private String decrypt(String encrypted, String key, int bookId, List<Integer> byttesps1, List<Integer> byttesps2) {
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
        if (list != null) {
            for (int i : list) {
                ps.append(getXorPs(bookId, i));
            }
        }
        return ps.toString();
    }

    private String getXorPs(int bookId, int value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(Integer.toString(bookId).getBytes());
            String mdf = new BigInteger(1, digest).toString(4);

            while (mdf.length() < 4) {
                mdf = "0" + mdf;
            }

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
            byte[] hexBytes = Hex.encode(utf8);

            String res = new String(hexBytes);
            if (res.length() == 2 && havePadding) {
                res = "00" + res;
            }

            hexResult.append(res);
        }
        return hexResult.toString();
    }

    public static class PositionObfuscator {
        private final String key;
        private final boolean base64EncodeOutput;

        public PositionObfuscator(String key, boolean base64EncodeOutput) {
            this.key = key;
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
                byte[] bytes = Base64.decode(obfuscated, Base64.DEFAULT);
                decoded = new String(bytes, StandardCharsets.UTF_8);
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