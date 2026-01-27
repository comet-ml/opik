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
 * Service provider for Ollama LLM provider.
 * Ollama uses OpenAI-compatible API format, so it delegates to CustomLlmClientGenerator
 * for client creation while maintaining its own provider identity.
 */
class OllamaServiceProvider implements LlmServiceProvider {

    private final CustomLlmClientGenerator clientGenerator;
    private final LlmProviderClientConfig config;

    OllamaServiceProvider(
            CustomLlmClientGenerator clientGenerator,
            LlmProviderFactory factory,
            LlmProviderClientConfig config) {
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
            LlmProviderClientApiConfig apiConfig,
            LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(apiConfig, modelParameters);
    }
}
