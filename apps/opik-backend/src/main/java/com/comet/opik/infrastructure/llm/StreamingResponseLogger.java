package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Reusable utility for logging complete assembled streaming responses.
 * Accumulates content chunks and logs the complete response when streaming finishes.
 */
@Slf4j
public class StreamingResponseLogger {
    private final StringBuilder accumulatedContent = new StringBuilder();
    private final String requestSummary;
    private final String model;

    public StreamingResponseLogger(String requestSummary, String model) {
        this.requestSummary = requestSummary;
        this.model = model;
    }

    /**
     * Append a content chunk to the accumulated response
     */
    public void appendContent(String content) {
        if (content != null) {
            accumulatedContent.append(content);
        }
    }

    /**
     * Log the complete assembled response with token usage information
     */
    public void logComplete(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "LLM Streaming Response Complete - Request: {}, Model: {}, Content: {}, InputTokens: {}, OutputTokens: {}, TotalTokens: {}",
                    requestSummary,
                    model,
                    accumulatedContent.toString(),
                    inputTokens,
                    outputTokens,
                    totalTokens);
        }
    }

    /**
     * Log the complete assembled response from ChatCompletionResponse
     */
    public void logComplete(ChatCompletionResponse finalResponse) {
        Integer inputTokens = null;
        Integer outputTokens = null;
        Integer totalTokens = null;

        if (finalResponse != null && finalResponse.usage() != null) {
            inputTokens = finalResponse.usage().promptTokens();
            outputTokens = finalResponse.usage().completionTokens();
            totalTokens = finalResponse.usage().totalTokens();
        }

        logComplete(inputTokens, outputTokens, totalTokens);
    }

    /**
     * Log error with any accumulated partial content.
     * Always logs at ERROR level. Includes partial content only when DEBUG is enabled.
     */
    public void logError(Throwable throwable) {
        if (log.isDebugEnabled() && !accumulatedContent.isEmpty()) {
            // DEBUG enabled: Include partial content (may be sensitive)
            log.error(
                    "LLM Streaming Response Error - Request: {}, Model: {}, Content: {}, Error: {}",
                    requestSummary,
                    model,
                    accumulatedContent,
                    throwable.getMessage(),
                    throwable);
        } else {
            // DEBUG disabled: No partial content (security)
            log.error("LLM Streaming Response Error - Request: {}, Model: {}, Error: {}",
                    requestSummary,
                    model,
                    throwable.getMessage(),
                    throwable);
        }
    }
}
