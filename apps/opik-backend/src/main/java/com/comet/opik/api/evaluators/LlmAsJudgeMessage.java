package com.comet.opik.api.evaluators;

import com.comet.opik.utils.LlmAsJudgeMessageContentDeserializer;
import com.comet.opik.utils.LlmAsJudgeMessageContentSerializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
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
                AutomationRuleEvaluator.View.Write.class}) @JsonSerialize(using = LlmAsJudgeMessageContentSerializer.class) @JsonDeserialize(using = LlmAsJudgeMessageContentDeserializer.class) @NotNull Object content){

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
     * Get content as a list of content parts.
     * @return the content as a properly typed list
     * @throws IllegalStateException if content is not a List or contains wrong types
     */
    @SuppressWarnings("unchecked")
    public List<LlmAsJudgeMessageContent> asContentList() {
        if (!(content instanceof List<?> list)) {
            throw new IllegalStateException("Content is not a List, got: " + content.getClass());
        }

        if (list.isEmpty()) {
            return List.of();
        }

        // Verify all elements are the correct type
        if (!(list.get(0) instanceof LlmAsJudgeMessageContent)) {
            throw new IllegalStateException(
                    "Content list contains wrong type. Expected LlmAsJudgeMessageContent, got: "
                            + list.get(0).getClass()
                            + ". This indicates a deserialization problem in the mapper.");
        }

        return (List<LlmAsJudgeMessageContent>) content;
    }
}
