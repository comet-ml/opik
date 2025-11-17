package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.List;

/**
 * Custom serializer for LlmAsJudgeMessage content field that handles both String and List types.
 * The content field is polymorphic and can be either:
 * - A String for simple text messages
 * - A List&lt;LlmAsJudgeMessageContent&gt; for multimodal messages (text, images, videos)
 */
public class LlmAsJudgeMessageContentSerializer extends JsonSerializer<Object> {

    public static final LlmAsJudgeMessageContentSerializer INSTANCE = new LlmAsJudgeMessageContentSerializer();

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof String stringValue) {
            gen.writeString(stringValue);
        } else if (value instanceof List<?> listValue) {
            // Delegate serialization of the list to Jackson's default serializer
            serializers.defaultSerializeValue(listValue, gen);
        } else {
            throw new IllegalStateException(
                    "Content must be either String or List<LlmAsJudgeMessageContent>, got: "
                            + value.getClass().getName());
        }
    }

    @Override
    public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider serializers,
            com.fasterxml.jackson.databind.jsontype.TypeSerializer typeSer) throws IOException {
        // For polymorphic serialization, just serialize without type info
        serialize(value, gen, serializers);
    }
}
