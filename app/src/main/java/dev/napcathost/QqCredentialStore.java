package dev.napcathost;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class QqCredentialStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "napcathost.qq.password-md5.v1";
    private static final String PREF_CIPHERTEXT = "qq_password_md5_ciphertext";
    private static final String PREF_IV = "qq_password_md5_iv";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte[] AAD = "dev.napcathost:qq-password-md5:v1"
            .getBytes(StandardCharsets.UTF_8);

    private final SharedPreferences preferences;

    QqCredentialStore(Context context) {
        preferences = HostConfig.preferences(context.getApplicationContext());
    }

    boolean hasSavedCredential() {
        return !preferences.getString(PREF_CIPHERTEXT, "").isEmpty()
                && !preferences.getString(PREF_IV, "").isEmpty();
    }

    void savePassword(String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw new GeneralSecurityException("QQ 密码不能为空");
        }
        String passwordMd5 = passwordMd5(password);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(AAD);
        byte[] ciphertext = cipher.doFinal(passwordMd5.getBytes(StandardCharsets.US_ASCII));
        preferences.edit()
                .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    String loadPasswordMd5() throws GeneralSecurityException {
        String ciphertextValue = preferences.getString(PREF_CIPHERTEXT, "");
        String ivValue = preferences.getString(PREF_IV, "");
        if (ciphertextValue.isEmpty() || ivValue.isEmpty()) {
            return null;
        }
        byte[] ciphertext;
        byte[] iv;
        try {
            ciphertext = Base64.decode(ciphertextValue, Base64.NO_WRAP);
            iv = Base64.decode(ivValue, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("QQ 密码凭据已损坏", e);
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        byte[] plaintext = cipher.doFinal(ciphertext);
        String result = new String(plaintext, StandardCharsets.US_ASCII);
        if (!result.matches("[0-9a-f]{32}")) {
            throw new GeneralSecurityException("QQ 密码凭据格式无效");
        }
        return result;
    }

    void clear() {
        preferences.edit().remove(PREF_CIPHERTEXT).remove(PREF_IV).apply();
    }

    static String passwordMd5(String password) throws GeneralSecurityException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new GeneralSecurityException("设备不支持 MD5", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("无法打开 Android Keystore", e);
        }
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
