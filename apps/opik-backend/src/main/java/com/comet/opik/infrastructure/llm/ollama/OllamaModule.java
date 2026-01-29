package com.comet.opik.infrastructure.llm.ollama;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmClientGenerator;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Guice module for Ollama LLM provider.
 * Ollama uses OpenAI-compatible API, so it reuses the CustomLlmClientGenerator
 * but registers under the OLLAMA provider type.
 */
public class OllamaModule extends AbstractModule {

    @Provides
    @Singleton
    @Named("ollamaServiceProvider")
    public LlmServiceProvider llmServiceProvider(
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("customLlmGenerator") CustomLlmClientGenerator clientGenerator,
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new OllamaServiceProvider(clientGenerator, llmProviderFactory, config);
    }
}
