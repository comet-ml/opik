package com.comet.opik.infrastructure.redis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.UUIDDeserializer;

import java.io.IOException;
import java.util.UUID;

/**
 * Tolerant UUID deserializer for Redis stream payloads.
 * <p>
 * Accepts both shapes:
 * <ul>
 *   <li>Plain string: {@code "6caf374f-6568-4c6f-aad0-257e0c7296a4"}</li>
 *   <li>Jackson polymorphic {@code As.WRAPPER_ARRAY}:
 *       {@code ["java.util.UUID", "6caf374f-6568-4c6f-aad0-257e0c7296a4"]}</li>
 * </ul>
 * <p>
 * Older opik-backend versions running on a different Redisson default-typing
 * configuration left messages of one shape in the {@code XPENDING} list of
 * Redis streams; the current version produces the other. Without this lenient
 * shim, {@code XAUTOCLAIM} fires {@code MismatchedInputException} every
 * {@code pending-message-duration} window for those stuck messages and never
 * makes progress on them.
 */
public class LenientUUIDDeserializer extends UUIDDeserializer {

    public static final LenientUUIDDeserializer INSTANCE = new LenientUUIDDeserializer();

    @Override
    public UUID deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            // Wrapper-array form produced when Jackson default-typing wraps the value as
            // ["java.util.UUID", "<uuid>"]. Skip the type-id token, parse the value, then
            // advance past the closing END_ARRAY so the parent reader stays positioned.
            p.nextToken();
            p.nextToken();
            UUID result = super.deserialize(p, ctxt);
            p.nextToken();
            return result;
        }
        return super.deserialize(p, ctxt);
    }
}
