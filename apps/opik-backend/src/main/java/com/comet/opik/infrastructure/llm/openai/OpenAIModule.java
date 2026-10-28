package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.customllm.LlmHttpClientBuilders;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.http.HttpClient;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class OpenAIModule extends AbstractModule {
    @Provides
    @Singleton
    @Named("openaiGenerator")
    public OpenAIClientGenerator clientGenerator(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull HttpClient responsesApiHttpClient, @NonNull LlmHttpClientBuilders httpClientBuilders) {
        return new OpenAIClientGenerator(config, responsesApiHttpClient, httpClientBuilders);
    }

    /**
     * Replaces the OpenAI SDK's own transport, which follows redirects: they would take a request past the base URL
     * check. Every connection is also resolved through the destination guard.
     */
    @Provides
    @Singleton
    public HttpClient responsesApiHttpClient(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new OkHttpClient(DestinationGuardDns.clientBuilder(DestinationGuard.builder()
                .mode(config.getDestinationGuard())
                .scheme(config.getDestinationScheme())
                .build())
                .followRedirects(false)
                .followSslRedirects(false)
                .build());
    }

    @Provides
    @Singleton
    @Named("openai")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("openaiGenerator") OpenAIClientGenerator clientGenerator) {
        return new OpenAILlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
