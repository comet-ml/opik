package com.comet.opik.utils.slim;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
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

    /**
     * Writes nodes as {@code JsonNode.toString()} does: a default mapper's writer, which serializes each call with its
     * own provider. It does not flush, so writing into the caller's generator leaves flushing to the caller.
     */
    ObjectWriter NODE_WRITER = new ObjectMapper().writer().without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);

    void write(JsonGenerator generator) throws IOException;

    /**
     * Writes a node as {@code node.toString()} does: with {@link #NODE_WRITER}, not the caller's mapper, so the text
     * does not depend on the configuration of the mapper that writes the enclosing value.
     */
    static void writeNode(@NonNull JsonNode node, @NonNull JsonGenerator generator) throws IOException {
        NODE_WRITER.writeValue(generator, node);
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
