package com.comet.opik.domain.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import dev.langchain4j.model.chat.ChatModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmProviderFactory {

    String ERROR_MODEL_NOT_SUPPORTED = "model not supported %s";

    void register(LlmProvider llmProvider, LlmServiceProvider service);

    LlmProviderService getService(String workspaceId, String model);

    ChatModel getLanguageModel(String workspaceId, LlmAsJudgeModelParameters modelParameters);

    LlmProvider getLlmProvider(String model);

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
