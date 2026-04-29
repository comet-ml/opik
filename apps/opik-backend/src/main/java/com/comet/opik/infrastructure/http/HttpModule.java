package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Provides;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.jackson.Jackson;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class HttpModule extends DropwizardAwareModule<OpikConfiguration> {

    public static final String CLIENT_NAME = "opik-shared-jersey-client";

    /**
     * Provides the singleton {@link Client} for outbound HTTP calls.
     * <p>
     * Inbound REST API and outbound HTTP calls follow different serialization strategies. The environment
     * ObjectMapper carries customizations targeting the inbound API (e.g. {@code SnakeCaseStrategy}) which
     * must NOT leak into outbound calls. Overriding with a clean {@link Jackson#newObjectMapper()} keeps
     * the two concerns isolated.
     */
    @Provides
    @Singleton
    public Client client(@Config("jerseyClient") JerseyClientConfiguration config) {
        var builder = new JerseyClientBuilder(environment());
        return buildClient(builder, config);
    }

    /**
     * Used by the production {@link #client} provider and by some test code paths that build
     * an equivalent {@link Client} without a full Dropwizard {@code Environment}. Encapsulates the
     * dedicated outbound {@link Jackson#newObjectMapper()} etc.so the two paths stay close to identical.
     */
    @VisibleForTesting
    public static Client buildClient(JerseyClientBuilder builder, JerseyClientConfiguration config) {
        return builder
                .using(config)
                .using(Jackson.newObjectMapper())
                .build(CLIENT_NAME);
    }

    @Override
    protected void configure() {
        // Use handler-based CORS (CrossOriginHandler). Jetty filter is deprecated.
        CorsFactory.registerHandlerIfEnabled(configuration(), environment());
    }
}
