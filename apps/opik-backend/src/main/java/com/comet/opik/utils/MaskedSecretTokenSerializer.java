package com.comet.opik.utils;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class MaskedSecretTokenSerializer extends JsonSerializer<String> {

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            String decrypted;
            try {
                decrypted = EncryptionUtils.decrypt(value);
            } catch (Exception e) {
                log.debug("Failed to decrypt secret token, falling back to raw value", e);
                decrypted = value;
            }
            gen.writeString(EncryptionUtils.maskApiKey(decrypted));
        }
    }
}
