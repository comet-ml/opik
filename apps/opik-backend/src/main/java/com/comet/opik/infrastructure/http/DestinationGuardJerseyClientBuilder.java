package com.comet.opik.infrastructure.http;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import io.dropwizard.client.ConfiguredCloseableHttpClient;
import io.dropwizard.client.DropwizardApacheConnector;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.core.setup.Environment;
import lombok.NonNull;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;

/**
 * Builds the shared client so a request carrying {@link DestinationGuardDnsResolver#PROPERTY} connects only to
 * addresses that guard approved. Requests without it, all the internal ones, resolve as before.
 */
public class DestinationGuardJerseyClientBuilder extends JerseyClientBuilder {

    private final DestinationGuardDnsResolver resolver = new DestinationGuardDnsResolver();
    private boolean chunkedEncodingEnabled;

    public DestinationGuardJerseyClientBuilder(@NonNull Environment environment) {
        super(environment);
        using(resolver);
    }

    public DestinationGuardJerseyClientBuilder(@NonNull MetricRegistry metricRegistry) {
        super(metricRegistry);
        using(resolver);
    }

    @Override
    public JerseyClientBuilder using(@NonNull JerseyClientConfiguration configuration) {
        // the parent keeps its configuration private, and the connector below is built as the parent builds it
        chunkedEncodingEnabled = configuration.isChunkedEncodingEnabled();
        return super.using(configuration);
    }

    @Override
    protected DropwizardApacheConnector createDropwizardApacheConnector(ConfiguredCloseableHttpClient client) {
        return new DropwizardApacheConnector(client.getClient(), client.getDefaultRequestConfig(),
                chunkedEncodingEnabled) {
            // the async variant runs this one on the calling thread too
            @Override
            public ClientResponse apply(ClientRequest request) {
                return resolver.resolveWith(
                        request.resolveProperty(DestinationGuardDnsResolver.PROPERTY, DestinationGuard.class),
                        () -> super.apply(request));
            }
        };
    }
}
