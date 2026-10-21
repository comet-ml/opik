package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Named;
import lombok.NonNull;

/**
 * Service provider for OpenAI-compatible LLM providers.
 * This includes both Custom LLM and Bedrock providers, which share the same
 * OpenAI-compatible API format and client implementation.
 */
class OpenAICompatibleServiceProvider implements LlmServiceProvider {

    private final CustomLlmClientGenerator clientGenerator;

    OpenAICompatibleServiceProvider(
            @Named("customLlmGenerator") CustomLlmClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.CUSTOM_LLM, this);
        factory.register(LlmProvider.BEDROCK, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig config) {
        // Ollama keys land here as well, not in OllamaServiceProvider: all three store their models under the
        // custom-llm/ prefix, so the factory routes them as CUSTOM_LLM and only the key's own type tells them apart.
        return new CustomLlmProvider(
                clientGenerator.newCustomLlmClient(config), config.configuration(), config.provider());
    }

    @Override
    public ChatModel getLanguageModel(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(config, modelParameters);
    }
}
