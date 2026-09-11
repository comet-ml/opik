package com.comet.opik.infrastructure;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@UtilityClass
public class EncryptionUtils {

    private static final String ALGO = "AES";
    // AES-GCM for larger, structured payloads (e.g. auth_config JSON): the legacy no-IV mode is
    // deterministic, which is acceptable for short random keys but not for predictable plaintext.
    private static final String GCM_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder mimeEncoder = Base64.getMimeEncoder();
    private static final Base64.Decoder mimeDecoder = Base64.getMimeDecoder();
    private static Key key;

    public static void setConfig(@NonNull OpikConfiguration config) {
        byte[] keyBytes = config.getEncryption().getKey().getBytes(StandardCharsets.UTF_8);
        key = new SecretKeySpec(keyBytes, ALGO);
    }

    public static String encrypt(@NonNull String data) {
        try {
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] encVal = c.doFinal(data.getBytes());
            return mimeEncoder.encodeToString(encVal);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException | IllegalBlockSizeException
                | BadPaddingException ex) {
            throw new SecurityException("Failed to encrypt. " + ex.getMessage(), ex);
        }
    }

    public static String decrypt(@NonNull String encryptedData) {
        try {
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key);
            byte[] decordedValue = mimeDecoder.decode(encryptedData);
            byte[] decValue = c.doFinal(decordedValue);
            return new String(decValue);
        } catch (BadPaddingException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | IllegalBlockSizeException ex) {
            throw new SecurityException("Failed to decrypt. " + ex.getMessage(), ex);
        }
    }

    public static String encryptGcm(@NonNull String data) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new SecurityException("Failed to encrypt. " + ex.getMessage(), ex);
        }
    }

    public static String decryptGcm(@NonNull String encryptedData) {
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedData);
            Cipher cipher = Cipher.getInstance(GCM_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_LENGTH));
            byte[] decrypted = cipher.doFinal(payload, GCM_IV_LENGTH, payload.length - GCM_IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new SecurityException("Failed to decrypt. " + ex.getMessage(), ex);
        }
    }

    public static String maskApiKey(@NonNull String apiKey) {
        return apiKey.length() <= 12
                ? StringUtils.repeat('*', apiKey.length())
                : apiKey.substring(0, 3) + StringUtils.repeat('*', apiKey.length() - 6)
                        + apiKey.substring(apiKey.length() - 3);
    }
}
