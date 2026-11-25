package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import lombok.Builder;
import lombok.NonNull;

import java.io.IOException;

import static com.comet.opik.utils.TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;

/**
 * A payload node's full or slim JSON, written into the caller's generator when the enclosing value is serialized. See
 * {@link TruncationUtils#fullJsonSerializable(JsonNode)} and {@link TruncationUtils#slimJsonSerializable(JsonNode)}.
 */
@Builder(toBuilder = true)
record PayloadJson(@NonNull JsonNode node, boolean slim) implements JsonSerializable {

    /**
     * A default mapper's writer, as {@code JsonNode.toString()} uses, without flushing after each value: the payload
     * is written into the enclosing row, which the caller flushes. Each write gets its own serializer provider.
     */
    static final ObjectWriter NODE_WRITER = new ObjectMapper().writer()
            .without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);

    @Override
    public void serialize(JsonGenerator generator, SerializerProvider provider) throws IOException {
        if (slim) {
            NODE_WRITER.writeValue(new TruncatingGenerator(generator, DEFAULT_SLIM_STRING_MAX_LENGTH), node);
            return;
        }
        NODE_WRITER.writeValue(generator, node);
    }

    @Override
    public void serializeWithType(JsonGenerator generator, SerializerProvider provider,
            TypeSerializer typeSerializer) throws IOException {
        serialize(generator, provider);
    }
}
