package com.comet.opik.infrastructure;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

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

@UtilityClass
public class EncryptionUtils {

    private static final String ALGO = "AES";
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

    public static String maskApiKey(@NonNull String apiKey) {
        return apiKey.length() <= 12
                ? StringUtils.repeat('*', apiKey.length())
                : apiKey.substring(0, 3) + StringUtils.repeat('*', apiKey.length() - 6)
                        + apiKey.substring(apiKey.length() - 3);
    }
}
