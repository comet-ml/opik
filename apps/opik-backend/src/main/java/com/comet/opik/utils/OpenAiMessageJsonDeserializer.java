package com.comet.opik.utils;

import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.FunctionMessage;
import dev.langchain4j.model.openai.internal.chat.ImageDetail;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.ToolMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

/**
 * The Message interface of openai4j has not appropriate deserialization support for all its polymorphic implementors
 * such as UserMessage, AssistantMessage etc. so deserialization fails.
 * As we can't annotate them Message interface with JsonTypeInfo and JsonSubTypes, solving this issue by creating
 * a custom deserializer.
 */
@Slf4j
public class OpenAiMessageJsonDeserializer extends JsonDeserializer<Message> {

    public static final OpenAiMessageJsonDeserializer INSTANCE = new OpenAiMessageJsonDeserializer();

    @Override
    public Message deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
        JsonNode jsonNode = jsonParser.readValueAsTree();
        var role = context.readTreeAsValue(jsonNode.get("role"), Role.class);
        return switch (role) {
            case SYSTEM -> context.readTreeAsValue(jsonNode, SystemMessage.class);
            case USER -> deserializeUserMessage(jsonNode);
            case ASSISTANT -> context.readTreeAsValue(jsonNode, AssistantMessage.class);
            case TOOL -> context.readTreeAsValue(jsonNode, ToolMessage.class);
            case FUNCTION -> context.readTreeAsValue(jsonNode, FunctionMessage.class);
        };
    }

    private OpikUserMessage deserializeUserMessage(JsonNode jsonNode) {
        var builder = OpikUserMessage.builder();

        var nameNode = jsonNode.get("name");
        if (nameNode != null && !nameNode.isNull()) {
            builder.name(nameNode.asText());
        }

        var contentNode = jsonNode.get("content");

        if (contentNode == null || contentNode.isNull()) {
            return builder.build();
        }

        if (contentNode.isTextual()) {
            builder.content(contentNode.asText());
            return builder.build();
        }

        if (contentNode.isArray()) {
            for (var partNode : contentNode) {
                var type = partNode.path("type").asText();
                switch (type) {
                    case "text", "input_text" -> builder.addText(partNode.path("text").asText(""));
                    case "image_url" -> handleImageUrlPart(builder, partNode.path("image_url"));
                    case "video_url" -> handleVideoUrlPart(builder, partNode.path("video_url"));
                    case "audio_url" -> handleAudioUrlPart(builder, partNode.path("audio_url"));
                    default -> log.warn("Skipping part of user message due to unknown type: '{}'", type);
                }
            }
            return builder.build();
        }

        builder.content(contentNode.toString());
        return builder.build();
    }

    private void handleImageUrlPart(OpikUserMessage.Builder builder, JsonNode imageUrlNode) {
        if (imageUrlNode == null || imageUrlNode.isNull()) {
            return;
        }

        var url = imageUrlNode.path("url").asText(null);
        if (StringUtils.isBlank(url)) {
            return;
        }

        var detailText = imageUrlNode.path("detail").asText(null);

        if (StringUtils.isBlank(detailText)) {
            builder.addImageUrl(url);
            return;
        }

        try {
            var detail = ImageDetail.valueOf(detailText.toUpperCase());
            builder.addImageUrl(url, detail);
        } catch (IllegalArgumentException exception) {
            log.warn("Error adding image with url '{}', detail: '{}'", url, detailText, exception);
            builder.addImageUrl(url);
        }
    }

    private void handleVideoUrlPart(OpikUserMessage.Builder builder, JsonNode videoUrlNode) {
        if (videoUrlNode == null || videoUrlNode.isNull()) {
            return;
        }

        var url = videoUrlNode.path("url").asText(null);
        if (StringUtils.isBlank(url)) {
            return;
        }

        builder.addVideoUrl(url);
    }

    private void handleAudioUrlPart(OpikUserMessage.Builder builder, JsonNode audioUrlNode) {
        if (audioUrlNode == null || audioUrlNode.isNull()) {
            return;
        }

        var url = audioUrlNode.path("url").asText(null);
        if (StringUtils.isBlank(url)) {
            return;
        }

        builder.addAudioUrl(url);
    }
}
