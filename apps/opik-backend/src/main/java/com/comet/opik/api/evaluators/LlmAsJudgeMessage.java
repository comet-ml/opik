package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.langchain4j.data.message.ChatMessageType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * Represents a message in an LLM-as-Judge evaluator.
 * Follows OpenAI's message format where content can be either:
 * - A string (for simple text messages
 * - An array of content parts (for multimodal messages with text, images, videos)
 *
 * This matches the OpenAI API format: UserContent = string | Array<TextPart | ImagePart | VideoPart>
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmAsJudgeMessage(
        @JsonView( {
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull ChatMessageType role,
        @JsonView({AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull Object content){

    /**
     * Check if content is a string.
     */
    public boolean isStringContent() {
        return content instanceof String;
    }

    /**
     * Check if content is an array of content parts.
     */
    public boolean isStructuredContent() {
        return content instanceof List<?>;
    }

    /**
     * Get content as a string (when it is one).
     * @throws ClassCastException if content is not a String
     */
    public String asString() {
        return (String) content;
    }

    /**
     * Get content as a list of content parts (when it is one).
     * Handles conversion from LinkedHashMap (when deserialized from DB) to LlmAsJudgeMessageContent.
     */
    @SuppressWarnings("unchecked")
    public List<LlmAsJudgeMessageContent> asContentList() {
        if (!(content instanceof List<?>)) {
            throw new IllegalStateException("Content is not a List");
        }

        List<?> rawList = (List<?>) content;
        if (rawList.isEmpty()) {
            return List.of();
        }

        // If already proper type, return as is
        if (rawList.get(0) instanceof LlmAsJudgeMessageContent) {
            return (List<LlmAsJudgeMessageContent>) content;
        }

        // Otherwise, convert from LinkedHashMap to LlmAsJudgeMessageContent
        return rawList.stream()
                .map(obj -> {
                    if (obj instanceof java.util.Map<?, ?> map) {
                        return LlmAsJudgeMessageContent.builder()
                                .type((String) map.get("type"))
                                .text((String) map.get("text"))
                                .imageUrl(map.get("image_url") != null
                                        ? convertToImageUrl(map.get("image_url"))
                                        : null)
                                .videoUrl(map.get("video_url") != null
                                        ? convertToVideoUrl(map.get("video_url"))
                                        : null)
                                .build();
                    }
                    throw new IllegalStateException("Unexpected content part type: " + obj.getClass());
                })
                .toList();
    }

    private static LlmAsJudgeMessageContent.ImageUrl convertToImageUrl(Object obj) {
        if (obj instanceof java.util.Map<?, ?> map) {
            return LlmAsJudgeMessageContent.ImageUrl.builder()
                    .url((String) map.get("url"))
                    .detail((String) map.get("detail"))
                    .build();
        }
        throw new IllegalStateException("Unexpected image_url type: " + obj.getClass());
    }

    private static LlmAsJudgeMessageContent.VideoUrl convertToVideoUrl(Object obj) {
        if (obj instanceof java.util.Map<?, ?> map) {
            return LlmAsJudgeMessageContent.VideoUrl.builder()
                    .url((String) map.get("url"))
                    .build();
        }
        throw new IllegalStateException("Unexpected video_url type: " + obj.getClass());
    }
}
