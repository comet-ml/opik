package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Named;
import lombok.NonNull;

class RequestyLlmServiceProvider implements LlmServiceProvider {
    private final OpenAIClientGenerator clientGenerator;

    RequestyLlmServiceProvider(
            @Named("requestyGenerator") OpenAIClientGenerator clientGenerator, LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.REQUESTY, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig config) {
        return new LlmProviderRequesty(clientGenerator.newOpenAiClient(config));
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        // The router only knows the bare vendor/model id, so the Opik-side prefix is dropped here.
        var routerModelParameters = modelParameters.toBuilder()
                .name(RequestyModelName.stripPrefix(modelParameters.name()))
                .build();
        return clientGenerator.newOpenAiChatLanguageModel(config, routerModelParameters);
    }
}
