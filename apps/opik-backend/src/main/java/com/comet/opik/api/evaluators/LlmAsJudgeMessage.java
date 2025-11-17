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
 * - A string (for simple text messages) - use the 'content' field
 * - An array of content parts (for multimodal messages with text, images, videos) - use the 'content_array' field
 *
 * This matches the OpenAI API format: UserContent = string | Array<TextPart | ImagePart | VideoPart>
 *
 * Note: Exactly one of 'content' or 'content_array' should be non-null.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmAsJudgeMessage(
        @JsonView( {
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull ChatMessageType role,
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) String content,
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) List<LlmAsJudgeMessageContent> contentArray){

    /**
     * Check if content is a string.
     */
    public boolean isStringContent() {
        return content != null;
    }

    /**
     * Check if content is an array of content parts.
     */
    public boolean isStructuredContent() {
        return contentArray != null;
    }

    /**
     * Get content as a string (when it is one).
     * @throws IllegalStateException if content is not a String
     */
    public String asString() {
        if (content == null) {
            throw new IllegalStateException("Content is not a string, use contentArray instead");
        }
        return content;
    }

    /**
     * Get content as a list of content parts.
     * @return the content as a properly typed list
     * @throws IllegalStateException if content is not a List
     */
    public List<LlmAsJudgeMessageContent> asContentList() {
        if (contentArray == null) {
            throw new IllegalStateException("Content is not an array, use content instead");
        }
        return contentArray;
    }
}
