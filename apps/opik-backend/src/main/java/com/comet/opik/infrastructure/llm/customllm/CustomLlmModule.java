package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class CustomLlmModule extends AbstractModule {
    @Provides
    @Singleton
    @Named("customLlmGenerator")
    public CustomLlmClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new CustomLlmClientGenerator(config);
    }

    @Provides
    @Singleton
    @Named("openAICompatible")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("customLlmGenerator") CustomLlmClientGenerator clientGenerator) {
        return new OpenAICompatibleServiceProvider(clientGenerator, llmProviderFactory);
    }
}
