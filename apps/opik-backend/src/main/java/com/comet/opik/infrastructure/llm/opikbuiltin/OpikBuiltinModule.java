package com.comet.opik.infrastructure.llm.opikbuiltin;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class OpikBuiltinModule extends AbstractModule {

    @Provides
    @Singleton
    @Named("opikBuiltin")
    public LlmServiceProvider opikBuiltinServiceProvider(
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("openaiGenerator") OpenAIClientGenerator clientGenerator,
            @NonNull @Config OpikConfiguration configuration,
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig llmProviderClientConfig) {
        return new OpikBuiltinServiceProvider(
                clientGenerator,
                llmProviderFactory,
                configuration.getBuiltinLlmProvider(),
                llmProviderClientConfig);
    }
}
