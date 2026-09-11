package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.time.Duration;

/**
 * Custom Duration deserializer that rejects empty strings but allows null values.
 * This ensures that empty strings ("") are treated as invalid input and not converted to null.
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class StrictDurationDeserializer extends JsonDeserializer<Duration> {

    public static final StrictDurationDeserializer INSTANCE = new StrictDurationDeserializer();

    @Override
    public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.getCurrentToken();

        if (token == JsonToken.VALUE_NULL) {
            return null; // Allow null values
        }

        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getValueAsString();

            if (value.isEmpty()) {
                return null;
            }

            try {
                return Duration.parse(value);
            } catch (Exception e) {
                throw JsonMappingException.from(parser,
                        "Cannot parse Duration from string '" + value + "': " + e.getMessage(), e);
            }
        }

        throw JsonMappingException.from(parser,
                "Expected string or null for Duration, but got: " + token);
    }
}
