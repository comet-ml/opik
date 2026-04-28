package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class AnthropicModule extends AbstractModule {

    @Provides
    @Singleton
    public AnthropicClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull LlmModelRegistryService registryService) {
        return new AnthropicClientGenerator(config, registryService);
    }

    @Provides
    @Singleton
    @Named("anthropic")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull AnthropicClientGenerator clientGenerator,
            @NonNull LlmModelRegistryService registryService) {
        return new AnthropicLlmServiceProvider(clientGenerator, llmProviderFactory, registryService);
    }

}
