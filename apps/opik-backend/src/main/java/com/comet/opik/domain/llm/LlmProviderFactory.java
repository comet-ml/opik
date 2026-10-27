package com.comet.opik.domain.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;

import java.util.Optional;

public interface LlmProviderFactory {

    String ERROR_MODEL_NOT_SUPPORTED = "model not supported %s";

    void register(LlmProvider llmProvider, LlmServiceProvider service);

    LlmProviderService getService(String workspaceId, String model);

    ChatModel getLanguageModel(String workspaceId, LlmAsJudgeModelParameters modelParameters);

    /**
     * Resolves the workspace's provider configuration (decrypted API key, headers, base URL) for the model.
     * For callers that reach a provider without a LangChain4j client, e.g. the OpenRouter Decisions API.
     */
    LlmProviderClientApiConfig getClientApiConfig(String workspaceId, String model);

    /**
     * Whether the model's requests go to a destination the workspace configured (a provider base URL) rather than one
     * Opik fixes: what such a destination answers is not echoed back beyond a parsed provider error.
     */
    boolean hasUserConfiguredDestination(String workspaceId, String model);

    LlmProvider getLlmProvider(String model);

    StructuredOutputStrategy getStructuredOutputStrategy(String model);

    /** The catalog params of a model the registry lists under OpenAI, Gemini or Vertex AI, or empty otherwise. */
    Optional<ModelParams> catalogParams(String model);

    /**
     * Returns the resolved model info for the given model name.
     * For the built-in provider, this returns the actual model name and span provider.
     * For other providers, returns the original model name and provider type.
     */
    ResolvedModelInfo getResolvedModelInfo(String model);

    record ResolvedModelInfo(String actualModel, String provider) {
    }
}
