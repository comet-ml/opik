package com.comet.opik.utils;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Objects;

@Slf4j
public class MaskedSecretTokenSerializer extends JsonSerializer<String> {

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        Objects.requireNonNull(gen, "gen must not be null");

        if (value == null) {
            gen.writeNull();
            return;
        }

        try {
            gen.writeString(EncryptionUtils.maskApiKey(EncryptionUtils.decrypt(value)));
        } catch (SecurityException e) {
            log.debug("Failed to decrypt API key for masking, returning masked value directly.", e);
            gen.writeString(EncryptionUtils.maskApiKey(value));
        }
    }
}
