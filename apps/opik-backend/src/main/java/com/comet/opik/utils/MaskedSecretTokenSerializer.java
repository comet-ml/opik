package com.comet.opik.utils;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

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
                decrypted = value;
            }
            gen.writeString(EncryptionUtils.maskApiKey(decrypted));
        }
    }
}
