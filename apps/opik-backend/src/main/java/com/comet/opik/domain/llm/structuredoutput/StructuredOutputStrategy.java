package com.comet.opik.domain.llm.structuredoutput;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.NonNull;

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
            @NonNull ChatRequest.Builder chatRequestBuilder,
            @NonNull List<ChatMessage> messages,
            @NonNull List<LlmAsJudgeOutputSchema> schema);

    static StructuredOutputStrategy getStrategy(LlmProvider provider, String modelName) {
        boolean isStructuredOutputSupported = switch (provider) {
            case OPEN_AI -> OpenaiModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case GEMINI -> GeminiModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case OPEN_ROUTER -> OpenRouterModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case VERTEX_AI -> VertexAIModelName.byQualifiedName(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case ANTHROPIC, BEDROCK, CUSTOM_LLM, OPIK_FREE -> false; // TODO: Should we pick a model that supports structured output?
        };

        return isStructuredOutputSupported ? new ToolCallingStrategy() : new InstructionStrategy();
    }
}
