package com.comet.opik.infrastructure.redis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.UUIDDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;

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
 * <p>
 * {@link #deserializeWithType(JsonParser, DeserializationContext, TypeDeserializer)}
 * is the load-bearing override: when the upstream {@link JsonJacksonCodec} enables
 * default typing, the property deserializer invokes that method (not
 * {@link #deserialize}) and the {@link com.fasterxml.jackson.databind.jsontype.impl.AsArrayTypeDeserializer}
 * fails before ever delegating to the value deserializer if the token is a bare
 * string. Intercepting the {@code VALUE_STRING} branch here is the only way to
 * make plain-string inputs tolerated.
 */
public class LenientUUIDDeserializer extends UUIDDeserializer {

    public static final LenientUUIDDeserializer INSTANCE = new LenientUUIDDeserializer();

    @Override
    public UUID deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            // Wrapper-array form produced when Jackson default-typing wraps the value as
            // ["java.util.UUID", "<uuid>"]. Skip the type-id token, parse the value, then
            // require the closing END_ARRAY so a malformed shape like
            // ["...", "<uuid>", "extra"] is rejected instead of leaving the parser
            // misaligned for the next field.
            p.nextToken();
            p.nextToken();
            UUID result = super.deserialize(p, ctxt);
            JsonToken next = p.nextToken();
            if (next != JsonToken.END_ARRAY) {
                throw ctxt.wrongTokenException(p, UUID.class, JsonToken.END_ARRAY,
                        "Expected end of UUID wrapper array, found " + next);
            }
            return result;
        }
        return super.deserialize(p, ctxt);
    }

    @Override
    public Object deserializeWithType(JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
            throws IOException {
        // Default path delegates to typeDeserializer, which under As.WRAPPER_ARRAY expects
        // START_ARRAY and rejects bare strings. Short-circuit when we see a VALUE_STRING so
        // legacy plain-string UUIDs in the stream PEL still parse.
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return deserialize(p, ctxt);
        }
        return typeDeserializer.deserializeTypedFromAny(p, ctxt);
    }
}
