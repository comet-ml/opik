package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.openai.LlmProviderOpenAi;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.inject.Named;

class OpenRouterLlmServiceProvider implements LlmServiceProvider {
    private final OpenAIClientGenerator clientGenerator;

    OpenRouterLlmServiceProvider(
            @Named("openrouterGenerator") OpenAIClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.OPEN_ROUTER, this);
    }

    @Override
    public LlmProviderService getService(String apiKey) {
        return new LlmProviderOpenAi(clientGenerator.newOpenAiClient(apiKey));
    }

    @Override
    public ChatLanguageModel getLanguageModel(String apiKey,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.newOpenAiChatLanguageModel(apiKey, modelParameters);
    }
}
