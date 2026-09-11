package com.ezbookkeeping.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureSettings {
    private static final String PREFS = "secure_settings";
    private static final String KEY_ALIAS = "easy_bookkeeping_api_key";
    private static final String KEY_ORIGIN = "server_origin";
    private static final String KEY_TOKEN = "encrypted_token";
    private static final String KEY_ACCOUNT_CACHE = "encrypted_account_cache";

    private SecureSettings() {}

    static void saveLogin(Context context, String origin, String token) throws Exception {
        preferences(context).edit()
                .putString(KEY_ORIGIN, normalizeOrigin(origin))
                .putString(KEY_TOKEN, encrypt(token))
                .apply();
    }

    static String origin(Context context) {
        return preferences(context).getString(KEY_ORIGIN, "");
    }

    static String token(Context context) {
        return decryptOrEmpty(preferences(context).getString(KEY_TOKEN, ""));
    }

    static boolean isConfigured(Context context) {
        return !origin(context).isEmpty() && !token(context).isEmpty();
    }

    static void clearLogin(Context context) {
        preferences(context).edit()
                .remove(KEY_ORIGIN)
                .remove(KEY_TOKEN)
                .remove(KEY_ACCOUNT_CACHE)
                .apply();
        WebViewActivity.clearWebSession();
    }

    static void saveAccountCache(Context context, String json) {
        try {
            preferences(context).edit().putString(KEY_ACCOUNT_CACHE, encrypt(json)).apply();
        } catch (Exception ignored) {
            // A failed cache write must never break a successfully completed API call.
        }
    }

    static String accountCache(Context context) {
        return decryptOrEmpty(preferences(context).getString(KEY_ACCOUNT_CACHE, ""));
    }

    static String normalizeOrigin(String value) {
        String origin = value == null ? "" : value.trim();
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        if (!origin.startsWith("https://")) {
            throw new IllegalArgumentException("服务器地址必须以 https:// 开头");
        }
        if (origin.endsWith("/api")) {
            throw new IllegalArgumentException("只需填写服务器地址，不要包含 /api");
        }
        return origin;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer payload = ByteBuffer.allocate(4 + iv.length + cipherText.length);
        payload.putInt(iv.length).put(iv).put(cipherText);
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    }

    private static String decryptOrEmpty(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            ByteBuffer payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            int ivLength = payload.getInt();
            if (ivLength < 12 || ivLength > 16 || payload.remaining() <= ivLength) {
                return "";
            }
            byte[] iv = new byte[ivLength];
            payload.get(iv);
            byte[] cipherText = new byte[payload.remaining()];
            payload.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey encryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
