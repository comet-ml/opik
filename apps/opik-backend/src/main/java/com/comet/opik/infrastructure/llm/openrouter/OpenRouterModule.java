package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class OpenRouterModule extends AbstractModule {
    @Provides
    @Singleton
    public OpenAIClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        config.setOpenAiClient(new LlmProviderClientConfig.OpenAiClientConfig("https://openrouter.ai/api/v1"));
        return new OpenAIClientGenerator(config);
    }

    @Provides
    @Singleton
    @Named("openrouter")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull OpenAIClientGenerator clientGenerator) {
        return new OpenRouterLlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
