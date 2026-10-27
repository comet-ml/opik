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
     * Shared by every langchain4j model calling a destination a user configured, each connection going only to
     * addresses the destination guard approves. HTTP/1.1 only, as vLLM and other FastAPI servers speak only that.
     */
    @Provides
    @Singleton
    @Named("customLlmHttpClient")
    public OkHttpClient customLlmHttpClient(@NonNull ProviderBaseUrlGuard providerBaseUrlGuard) {
        return llmHttpClient(DestinationGuardDns.clientBuilder(providerBaseUrlGuard.destinationGuard())
                .protocols(List.of(Protocol.HTTP_1_1)), "guarded");
    }

    /**
     * Shared by every langchain4j model calling a destination the operator or Opik configured; it may be internal. As
     * with the JDK client, HTTP/2 is used where the server offers it over TLS.
     */
    @Provides
    @Singleton
    @Named("unguardedLlmHttpClient")
    public OkHttpClient unguardedLlmHttpClient() {
        return llmHttpClient(new OkHttpClient.Builder(), "unguarded");
    }

    /** The HTTP client builders every langchain4j model is given, over the two shared clients. */
    @Provides
    @Singleton
    public LlmHttpClientBuilders llmHttpClientBuilders(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull @Named("customLlmHttpClient") OkHttpClient guardedClient,
            @NonNull @Named("unguardedLlmHttpClient") OkHttpClient unguardedClient) {
        return new LlmHttpClientBuilders(guardedClient, unguardedClient,
                Math.toIntExact(config.getProviderErrorBodyMaxSize().toBytes()));
    }

    @Provides
    @Singleton
    @Named("customLlmGenerator")
    public CustomLlmClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull ProviderBaseUrlGuard providerBaseUrlGuard,
            @NonNull AuthTokenProvider authTokenProvider,
            @NonNull LlmHttpClientBuilders httpClientBuilders) {
        return new CustomLlmClientGenerator(config, providerBaseUrlGuard, authTokenProvider, httpClientBuilders);
    }

    /**
     * The rest keeps what the JDK client did: no redirects, no retry of a request on a broken connection, which could
     * send a model call twice, no cap on concurrent calls, which streams would otherwise queue behind, and daemon
     * threads. Each model's client derives from this one, so
     * all of them share its connection pool and dispatcher.
     */
    private static OkHttpClient llmHttpClient(OkHttpClient.Builder builder, String name) {
        var dispatcher = new Dispatcher(Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setNameFormat("llm-http-%s-%%d".formatted(name))
                .setDaemon(true)
                .build()));
        dispatcher.setMaxRequests(Integer.MAX_VALUE);
        dispatcher.setMaxRequestsPerHost(Integer.MAX_VALUE);
        return builder
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .dispatcher(dispatcher)
                .build();
    }

    @Provides
    @Singleton
    @Named("openAICompatible")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("customLlmGenerator") CustomLlmClientGenerator clientGenerator) {
        return new OpenAICompatibleServiceProvider(clientGenerator, llmProviderFactory);
    }
}
