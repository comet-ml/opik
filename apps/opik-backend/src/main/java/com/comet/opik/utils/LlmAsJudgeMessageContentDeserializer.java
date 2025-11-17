package com.comet.opik.utils;

import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer for LlmAsJudgeMessage content field that handles both String and List types.
 * The content field is polymorphic and can be either:
 * - A String for simple text messages
 * - A List&lt;LlmAsJudgeMessageContent&gt; for multimodal messages (text, images, videos)
 */
public class LlmAsJudgeMessageContentDeserializer extends JsonDeserializer<Object> {

    public static final LlmAsJudgeMessageContentDeserializer INSTANCE = new LlmAsJudgeMessageContentDeserializer();

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isTextual()) {
            return node.asText();
        } else if (node.isArray()) {
            List<LlmAsJudgeMessageContent> contentList = new ArrayList<>();
            for (JsonNode element : node) {
                contentList.add(p.getCodec().treeToValue(element, LlmAsJudgeMessageContent.class));
            }
            return contentList;
        } else {
            throw new IllegalStateException(
                    "Content must be either a string or an array, got: " + node.getNodeType());
        }
    }
}
