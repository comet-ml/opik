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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
     * Shared by every langchain4j model, each connection going only to addresses the destination guard approves.
     * HTTP/1.1 only, as vLLM and other FastAPI servers speak only that.
     */
    @Provides
    @Singleton
    @Named("llmHttpClient")
    public OkHttpClient llmHttpClient(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull ProviderBaseUrlGuard providerBaseUrlGuard) {
        return newLlmHttpClient(config, DestinationGuardDns.clientBuilder(providerBaseUrlGuard.destinationGuard())
                .protocols(List.of(Protocol.HTTP_1_1)));
    }

    /** The HTTP client builder every langchain4j model is given, over the shared client. */
    @Provides
    @Singleton
    public LlmHttpClientBuilders llmHttpClientBuilders(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config,
            @NonNull @Named("llmHttpClient") OkHttpClient client) {
        return new LlmHttpClientBuilders(client,
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
     * send a model call twice, and daemon threads. Each model's client derives from this one, so all of them share its
     * connection pool and dispatcher.
     *
     * <p>The dispatcher runs at most the configured number of asynchronous calls, overall and per host, and queues the
     * rest itself. It hands a call to the executor only while under that limit, and there are at least as many threads
     * as the limit, so a call never waits for a thread: the executor's queue only holds a call promoted by a thread
     * that is still finishing the previous one. The queue is unbounded so the executor never rejects a call, which
     * OkHttp would report as an interrupted call.
     */
    private static OkHttpClient newLlmHttpClient(LlmProviderClientConfig config, OkHttpClient.Builder builder) {
        var executor = new ThreadPoolExecutor(config.getHttpClientMaxThreads(), config.getHttpClientMaxThreads(),
                config.getHttpClientThreadKeepAlive().toMilliseconds(), TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .setNameFormat("llm-http-%d")
                        .setDaemon(true)
                        .build());
        // threads are started as calls need them and end once idle, so an idle client holds none
        executor.allowCoreThreadTimeOut(true);
        var dispatcher = new Dispatcher(executor);
        dispatcher.setMaxRequests(config.getHttpClientMaxRequests());
        dispatcher.setMaxRequestsPerHost(config.getHttpClientMaxRequestsPerHost());
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
