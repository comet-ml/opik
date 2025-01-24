package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class AnthropicLlmServiceProvider implements LlmServiceProvider {

    private final AnthropicClientGenerator clientGenerator;

    AnthropicLlmServiceProvider(@NonNull AnthropicClientGenerator clientGenerator,
            @NonNull LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.ANTHROPIC, this);
    }

    @Override
    public LlmProviderService getService(String apiKey) {
        return new LlmProviderAnthropic(clientGenerator.generate(apiKey));
    }

    @Override
    public ChatLanguageModel getLanguageModel(@NonNull String apiKey,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.generateChat(apiKey, modelParameters);
    }

}
