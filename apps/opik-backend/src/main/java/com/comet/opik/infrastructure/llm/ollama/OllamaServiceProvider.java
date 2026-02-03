package com.comet.opik.infrastructure.llm.ollama;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmClientGenerator;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmProvider;
import dev.langchain4j.model.chat.ChatModel;
import lombok.NonNull;

/**
 * Service provider for Ollama LLM provider with OpenAI-compatible API support.
 *
 * <p>This provider delegates to {@link CustomLlmClientGenerator} for client creation
 * since Ollama's /v1 endpoints are OpenAI-compatible. The base URL configured by users
 * must include the /v1 suffix for inference requests.
 *
 * <p>Example base URL for inference: http://localhost:11434/v1
 */
class OllamaServiceProvider implements LlmServiceProvider {

    private final CustomLlmClientGenerator clientGenerator;
    private final LlmProviderClientConfig config;

    OllamaServiceProvider(
            @NonNull CustomLlmClientGenerator clientGenerator,
            @NonNull LlmProviderFactory factory,
            @NonNull LlmProviderClientConfig config) {
        this.clientGenerator = clientGenerator;
        this.config = config;
        factory.register(LlmProvider.OLLAMA, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig apiConfig) {
        return new CustomLlmProvider(
                clientGenerator.newCustomLlmClient(apiConfig),
                apiConfig.configuration());
    }

    @Override
    public ChatModel getLanguageModel(
            @NonNull LlmProviderClientApiConfig apiConfig,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(apiConfig, modelParameters);
    }
}
