package com.comet.opik.utils.slim;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import lombok.NonNull;

import java.io.IOException;

/**
 * JSON written into the caller's generator when the enclosing value is serialized, rather than materialized as a
 * {@code String} first. Embedded in a row with {@code ObjectNode.putRawValue(field, new RawValue(json))}.
 */
@FunctionalInterface
interface WritableJson extends JsonSerializable {

    /** The provider {@code JsonNode.toString()} serializes with: a default mapper's. */
    SerializerProvider NODE_PROVIDER = new ObjectMapper().getSerializerProviderInstance();

    void write(JsonGenerator generator) throws IOException;

    /**
     * Writes a node as {@code node.toString()} does: with its provider, not the caller's, so the text does not depend
     * on the configuration of the mapper that writes the enclosing value.
     */
    static void writeNode(@NonNull JsonNode node, @NonNull JsonGenerator generator) throws IOException {
        node.serialize(generator, NODE_PROVIDER);
    }

    /** The full JSON of a node: the same text as {@code node.toString()}. */
    static WritableJson of(@NonNull JsonNode node) {
        return generator -> writeNode(node, generator);
    }

    @Override
    default void serialize(JsonGenerator generator, SerializerProvider provider) throws IOException {
        write(generator);
    }

    @Override
    default void serializeWithType(JsonGenerator generator, SerializerProvider provider,
            TypeSerializer typeSerializer) throws IOException {
        write(generator);
    }
}
