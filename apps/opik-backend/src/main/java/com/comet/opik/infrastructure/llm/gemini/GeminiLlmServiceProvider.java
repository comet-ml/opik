package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;

public class GeminiLlmServiceProvider implements LlmServiceProvider {

    private final GeminiClientGenerator clientGenerator;

    GeminiLlmServiceProvider(GeminiClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.GEMINI, this);
    }

    @Override
    public LlmProviderService getService(LlmProviderClientApiConfig config) {
        return new LlmProviderGemini(clientGenerator, config);
    }

    @Override
    public ChatModel getLanguageModel(LlmProviderClientApiConfig config,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(config, modelParameters);
    }
}
