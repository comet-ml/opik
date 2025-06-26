package com.comet.opik.domain.llm.structuredoutput;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeOutputSchema;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;

/**
 * A strategy for ensuring an LLM returns a structured JSON output.
 */
public interface StructuredOutputStrategy {

    /**
     * Applies the strategy to a chat request being built.
     * This may involve modifying the request builder (e.g., setting a response format) or
     * altering the list of messages (e.g., adding an instruction).
     *
     * @param chatRequestBuilder The builder for the chat request.
     * @param messages           The list of messages for the chat request.
     * @param schema             The desired output schema.
     * @return  The builder for the chat request
     */
    ChatRequest.Builder apply(
            ChatRequest.Builder chatRequestBuilder,
            List<ChatMessage> messages,
            List<LlmAsJudgeOutputSchema> schema);
}
