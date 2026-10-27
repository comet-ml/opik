package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.concurrent.Executors;

public class CustomLlmModule extends AbstractModule {

    @Provides
    @Singleton
    public ProviderBaseUrlGuard providerBaseUrlGuard(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return ProviderBaseUrlGuard.builder()
                .mode(config.getDestinationGuard())
                .scheme(config.getDestinationScheme())
                .build();
    }

    /**
     * Shared by every custom provider client, each connection going only to addresses the destination guard approves.
     * The rest keeps what the JDK client did: HTTP/1.1 (vLLM and other FastAPI servers speak only that), no redirects,
     * no retry of a request on a broken connection, which could send a model call twice, no cap on concurrent calls,
     * which streams would otherwise queue behind, and daemon threads.
     */
    @Provides
    @Singleton
    @Named("customLlmHttpClient")
    public OkHttpClient customLlmHttpClient(@NonNull ProviderBaseUrlGuard providerBaseUrlGuard) {
        var dispatcher = new Dispatcher(Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setNameFormat("custom-llm-http-%d")
                .setDaemon(true)
                .build()));
        dispatcher.setMaxRequests(Integer.MAX_VALUE);
        dispatcher.setMaxRequestsPerHost(Integer.MAX_VALUE);
        return DestinationGuardDns.clientBuilder(providerBaseUrlGuard.destinationGuard())
                .protocols(List.of(Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .dispatcher(dispatcher)
                .build();
    }

    @Provides
    @Singleton
    @Named("customLlmGenerator")
    public CustomLlmClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull ProviderBaseUrlGuard providerBaseUrlGuard,
            @NonNull AuthTokenProvider authTokenProvider,
            @NonNull @Named("customLlmHttpClient") OkHttpClient httpClient) {
        return new CustomLlmClientGenerator(config, providerBaseUrlGuard, authTokenProvider, httpClient);
    }

    @Provides
    @Singleton
    @Named("openAICompatible")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("customLlmGenerator") CustomLlmClientGenerator clientGenerator) {
        return new OpenAICompatibleServiceProvider(clientGenerator, llmProviderFactory);
    }
}
