package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
class VertextAILlmServiceProvider implements LlmServiceProvider {

    private final VertexAIClientGenerator clientGenerator;

    VertextAILlmServiceProvider(
            @Named("vertexAiGenerator") VertexAIClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.VERTEX_AI, this);
    }

    @Override
    public LlmProviderService getService(LlmProviderClientApiConfig apiKey) {
        return new LlmProviderVertexAI(clientGenerator, apiKey);
    }

    @Override
    public ChatModel getLanguageModel(LlmProviderClientApiConfig apiKey,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(apiKey, modelParameters);
    }
}
