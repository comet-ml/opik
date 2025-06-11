package com.comet.opik.infrastructure.llm.vllm;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Named;
import lombok.NonNull;

class VllmLlmServiceProvider implements LlmServiceProvider {

    private final VllmClientGenerator clientGenerator;

    VllmLlmServiceProvider(
            @Named("vllmGenerator") VllmClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.VLLM, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig apiKey) {
        return new LlmProviderVllm(clientGenerator.newVllmClient(apiKey));
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.newVllmChatLanguageModel(config, modelParameters);
    }
}
