package com.comet.opik.domain.llm.structuredoutput;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeOutputSchema;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
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

    static StructuredOutputStrategy getJsonOutputStrategy(LlmProvider provider, String modelName) {
        boolean isStructuredOutputSupported = switch (provider) {
            case OPEN_AI -> OpenaiModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case ANTHROPIC -> AnthropicModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case GEMINI -> GeminiModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case OPEN_ROUTER -> OpenRouterModelName.byValue(modelName)
                    .map(StructuredOutputSupported::isStructuredOutputSupported).orElse(false);
            case VERTEX_AI, VLLM -> false;
        };

        return isStructuredOutputSupported ? new ToolCallingStrategy() : new InstructionStrategy();
    }
}
