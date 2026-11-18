package com.viral.shortsmaker.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v10: the user's API keys are stored encrypted (AES-256-GCM) with a key that lives in the Android Keystore (it never
 * leaves the phone's secure hardware / keystore daemon and can't be copied out with the app's files). Old plain-text
 * keys from v1-v9 are migrated on first read and the plain copy is deleted. Same scheme as Jetpack's
 * EncryptedSharedPreferences, without the extra library. If the keystore is broken on a phone, the key is kept as
 * before (plain, app-private) rather than lost.
 */
public final class SecureKeys {
    private SecureKeys() {}

    private static final String ALIAS = "klipora_keys_v1";
    private static final String PREFIX = "enc_";

    public static synchronized String get(Context c, String name) {
        SharedPreferences sp = Prefs.sp(c);
        String enc = sp.getString(PREFIX + name, null);
        if (enc != null) {
            String v = decrypt(enc);
            if (v != null) return v;
        }
        String plain = sp.getString(name, "");
        if (plain != null && !plain.isEmpty()) put(c, name, plain);   // migrate v1-v9 plain text
        return plain == null ? "" : plain;
    }

    public static synchronized void put(Context c, String name, String value) {
        SharedPreferences.Editor ed = Prefs.sp(c).edit();
        String v = value == null ? "" : value.trim();
        String enc = v.isEmpty() ? null : encrypt(v);
        if (enc != null) ed.putString(PREFIX + name, enc).remove(name);
        else if (v.isEmpty()) ed.remove(PREFIX + name).remove(name);
        else ed.putString(name, v).remove(PREFIX + name);   // keystore unavailable on this phone: keep it working
        ed.apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    static String encrypt(String plain) {
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = ci.getIV(), ct = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    static String decrypt(String enc) {
        try {
            byte[] in = Base64.decode(enc, Base64.NO_WRAP);
            int ivLen = in[0];
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 1, ivLen));
            return new String(ci.doFinal(in, 1 + ivLen, in.length - 1 - ivLen), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
