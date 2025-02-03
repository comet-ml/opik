package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class GeminiModule extends AbstractModule {

    @Provides
    @Singleton
    public GeminiClientGenerator clientGenerator(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new GeminiClientGenerator(config);
    }

    @Provides
    @Singleton
    @Named("gemini")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull GeminiClientGenerator clientGenerator) {
        return new GeminiLlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
