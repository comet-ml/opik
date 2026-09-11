package com.comet.opik.utils;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class EncryptionDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
        return EncryptionUtils.encrypt(parser.getText());
    }
}