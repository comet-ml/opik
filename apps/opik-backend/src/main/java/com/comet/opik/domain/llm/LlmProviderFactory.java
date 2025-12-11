package com.comet.opik.domain.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;

public interface LlmProviderFactory {

    String ERROR_MODEL_NOT_SUPPORTED = "model not supported %s";

    void register(LlmProvider llmProvider, LlmServiceProvider service);

    LlmProviderService getService(String workspaceId, String model);

    ChatModel getLanguageModel(String workspaceId, LlmAsJudgeModelParameters modelParameters);

    LlmProvider getLlmProvider(String model);

    /**
     * Returns the resolved model info for the given model name.
     * For the built-in provider, this returns the actual model name and span provider.
     * For other providers, returns the original model name and provider type.
     */
    ResolvedModelInfo getResolvedModelInfo(String model);

    record ResolvedModelInfo(String actualModel, String provider) {
    }
}
