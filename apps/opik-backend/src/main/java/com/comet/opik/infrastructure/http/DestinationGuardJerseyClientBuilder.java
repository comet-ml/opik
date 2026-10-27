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
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Builds the shared client so a request carrying {@link DestinationGuardDnsResolver#PROPERTY} connects only to
 * addresses that guard approved, and closing its response ends the connection instead of reading the rest of the body.
 * Requests without it, all the internal ones, behave as before.
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
        return new DropwizardApacheConnector(new UserDestinationHttpClient(client.getClient()),
                client.getDefaultRequestConfig(),
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

    /**
     * Closing a response stream reads the rest of the body so the connection can be reused. A user destination
     * controls how long that takes, so its connection is cancelled instead; one already read to the end is unaffected.
     */
    @RequiredArgsConstructor
    private class UserDestinationHttpClient extends CloseableHttpClient {

        private final @NonNull CloseableHttpClient delegate;

        @Override
        protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request, HttpContext context)
                throws IOException {
            var response = delegate.execute(target, request, context);
            if (resolver.inScope() && response.getEntity() != null && request instanceof Cancellable cancellable) {
                response.setEntity(new HttpEntityWrapper(response.getEntity()) {
                    @Override
                    public InputStream getContent() throws IOException {
                        return new FilterInputStream(super.getContent()) {
                            @Override
                            public void close() {
                                cancellable.cancel();
                                try {
                                    super.close();
                                } catch (IOException ignored) {
                                    // the connection was just cancelled
                                }
                            }
                        };
                    }
                });
            }
            return response;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public void close(CloseMode closeMode) {
            delegate.close(closeMode);
        }
    }
}
