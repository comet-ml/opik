package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class OpenAIModule extends AbstractModule {
    @Provides
    @Singleton
    @Named("openaiGenerator")
    public OpenAIClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull ChatModelListener chatModelListener) {
        return new OpenAIClientGenerator(config, chatModelListener);
    }

    @Provides
    @Singleton
    @Named("openai")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("openaiGenerator") OpenAIClientGenerator clientGenerator) {
        return new OpenAILlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
