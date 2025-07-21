package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.time.Duration;

/**
 * Custom Duration deserializer that rejects empty strings but allows null values.
 * This ensures that empty strings ("") are treated as invalid input and not converted to null.
 */
public class StrictDurationDeserializer extends JsonDeserializer<Duration> {

    @Override
    public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.getCurrentToken();

        if (token == JsonToken.VALUE_NULL) {
            return null; // Allow null values
        }

        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getValueAsString();

            // Explicitly reject empty strings
            if (value.isEmpty()) {
                throw JsonMappingException.from(parser,
                        "Empty string is not a valid Duration value. Use null for no timeout, or provide a valid ISO-8601 duration (e.g., PT30M, PT2H, P1D).");
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