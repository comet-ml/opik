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
        return new CustomLlmProvider(clientGenerator.newCustomLlmClient(config), config.configuration());
    }

    @Override
    public ChatModel getLanguageModel(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(config, modelParameters);
    }
}
