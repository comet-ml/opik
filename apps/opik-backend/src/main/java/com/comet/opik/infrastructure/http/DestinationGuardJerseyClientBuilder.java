package com.comet.opik.infrastructure.http;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.httpclient5.InstrumentedHttpClientConnectionManager;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import io.dropwizard.client.ConfiguredCloseableHttpClient;
import io.dropwizard.client.DropwizardApacheConnector;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.core.setup.Environment;
import lombok.NonNull;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;

import java.util.function.Consumer;

/**
 * Builds the shared client so a request carrying {@link DestinationGuardDnsResolver#PROPERTY} connects only to
 * addresses that guard approved, and closing its response ends the connection instead of reading the rest of the body.
 * Requests without it behave as before. An internal client can set a {@link DestinationGuard.Mode#RELAXED} guard only
 * to get that close, see {@code InferenceClient}. It also hands the connection pool the parent builds, which the parent
 * otherwise gives only to the client, to a callback, so {@link HttpClientPoolMetrics} can read it.
 */
public class DestinationGuardJerseyClientBuilder extends JerseyClientBuilder {

    private final DestinationGuardDnsResolver resolver = new DestinationGuardDnsResolver();
    private boolean chunkedEncodingEnabled;

    /**
     * @param onPool called with the client's connection pool, once configured, when the client is built
     */
    public DestinationGuardJerseyClientBuilder(@NonNull Environment environment,
            @NonNull Consumer<PoolingHttpClientConnectionManager> onPool) {
        super(environment);
        // before using(...): the parent forwards those settings to the Apache builder it holds at the time
        setApacheHttpClientBuilder(new PoolHandingHttpClientBuilder(environment, onPool));
        using(resolver);
    }

    public DestinationGuardJerseyClientBuilder(@NonNull MetricRegistry metricRegistry,
            @NonNull Consumer<PoolingHttpClientConnectionManager> onPool) {
        super(metricRegistry);
        setApacheHttpClientBuilder(new PoolHandingHttpClientBuilder(metricRegistry, onPool));
        using(resolver);
    }

    public DestinationGuardJerseyClientBuilder(@NonNull MetricRegistry metricRegistry) {
        this(metricRegistry, pool -> {
        });
    }

    @Override
    public JerseyClientBuilder using(@NonNull JerseyClientConfiguration configuration) {
        // the parent keeps its configuration private, and the connector below is built as the parent builds it
        chunkedEncodingEnabled = configuration.isChunkedEncodingEnabled();
        return super.using(configuration);
    }

    private static class PoolHandingHttpClientBuilder extends HttpClientBuilder {

        private final Consumer<PoolingHttpClientConnectionManager> onPool;

        PoolHandingHttpClientBuilder(Environment environment, Consumer<PoolingHttpClientConnectionManager> onPool) {
            super(environment);
            this.onPool = onPool;
        }

        PoolHandingHttpClientBuilder(MetricRegistry metricRegistry,
                Consumer<PoolingHttpClientConnectionManager> onPool) {
            super(metricRegistry);
            this.onPool = onPool;
        }

        @Override
        protected InstrumentedHttpClientConnectionManager configureConnectionManager(
                InstrumentedHttpClientConnectionManager manager) {
            var configured = super.configureConnectionManager(manager);
            onPool.accept(configured);
            return configured;
        }
    }

    @Override
    protected DropwizardApacheConnector createDropwizardApacheConnector(ConfiguredCloseableHttpClient client) {
        return new DropwizardApacheConnector(new UserDestinationHttpClient(client.getClient(), resolver),
                client.getDefaultRequestConfig(),
                chunkedEncodingEnabled) {
            /** The async variant runs this one on the calling thread too. */
            @Override
            public ClientResponse apply(ClientRequest request) {
                return resolver.resolveWith(
                        request.resolveProperty(DestinationGuardDnsResolver.PROPERTY, DestinationGuard.class),
                        () -> super.apply(request));
            }
        };
    }
}
