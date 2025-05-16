package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Named;
import lombok.NonNull;

class OpenAILlmServiceProvider implements LlmServiceProvider {

    private final OpenAIClientGenerator clientGenerator;

    OpenAILlmServiceProvider(
            @Named("openaiGenerator") OpenAIClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.OPEN_AI, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig apiKey) {
        return new LlmProviderOpenAi(clientGenerator.newOpenAiClient(apiKey));
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.newOpenAiChatLanguageModel(config, modelParameters);
    }
}
