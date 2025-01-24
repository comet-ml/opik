package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class GeminiLlmServiceProvider implements LlmServiceProvider {

    private final GeminiClientGenerator clientGenerator;

    GeminiLlmServiceProvider(GeminiClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.GEMINI, this);
    }

    @Override
    public LlmProviderService getService(String apiKey) {
        return new LlmProviderGemini(clientGenerator, apiKey);
    }

    @Override
    public ChatLanguageModel getLanguageModel(String apiKey,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(apiKey, modelParameters);
    }
}
