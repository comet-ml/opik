package com.comet.opik.infrastructure;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;

@ImplementedBy(EncryptionServiceImpl.class)
public interface EncryptionService {
    String encrypt(String data);
    String decrypt(String encryptedData);
}

@Singleton
class EncryptionServiceImpl implements EncryptionService {

    private static final String ALGO = "AES";
    private final Base64.Encoder mimeEncoder = Base64.getMimeEncoder();
    private final Base64.Decoder mimeDecoder = Base64.getMimeDecoder();
    @NonNull private final Key key;

    @Inject
    public EncryptionServiceImpl(@NonNull OpikConfiguration config) {
        byte[] keyBytes = config.getEncryption().getKey().getBytes(StandardCharsets.UTF_8);
        key = new SecretKeySpec(keyBytes, ALGO);

        System.out.println("Conf1: " + config.getEncryption().toString());
    }

    @Override
    public String encrypt(String data) {
        try {
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] encVal = c.doFinal(data.getBytes());
            return mimeEncoder.encodeToString(encVal);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to encrypt. " + ex.getMessage(), ex);
        }
    }

    @Override
    public String decrypt(String encryptedData) {
        try {
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key);
            byte[] decordedValue = mimeDecoder.decode(encryptedData);
            byte[] decValue = c.doFinal(decordedValue);
            return new String(decValue);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to decrypt. " + ex.getMessage(), ex);
        }
    }
}
