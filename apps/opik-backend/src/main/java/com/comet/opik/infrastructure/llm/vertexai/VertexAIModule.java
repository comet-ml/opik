package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class VertexAIModule extends AbstractModule {

    @Provides
    @Singleton
    public VertexAIClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new VertexAIClientGenerator(config);
    }

    @Provides
    @Singleton
    @Named("vertexAi")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull VertexAIClientGenerator clientGenerator) {
        return new VertextAILlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
