package com.comet.opik.infrastructure;

import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class EncryptionUtils {

    private static final String ALGO = "AES";
    private static final Base64.Encoder mimeEncoder = Base64.getMimeEncoder();
    private static final Base64.Decoder mimeDecoder = Base64.getMimeDecoder();
    private static Key key;

    public static void setConfig(@NonNull OpikConfiguration config) {
        byte[] keyBytes = config.getEncryption().getKey().getBytes(StandardCharsets.UTF_8);
        key = new SecretKeySpec(keyBytes, ALGO);
    }

    public static String encrypt(@NotBlank String data) {
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

    public static String decrypt(@NotBlank String encryptedData) {
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
}
