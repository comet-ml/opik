package com.comet.opik.infrastructure.llm.vllm;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class VllmModule extends AbstractModule {
    @Provides
    @Singleton
    @Named("vllmGenerator")
    public VllmClientGenerator clientGenerator(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new VllmClientGenerator(config);
    }

    @Provides
    @Singleton
    @Named("vllm")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("vllmGenerator") VllmClientGenerator clientGenerator) {
        return new VllmLlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
