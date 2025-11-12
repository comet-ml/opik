package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class HttpModule extends DropwizardAwareModule<OpikConfiguration> {
    @Provides
    @Singleton
    public Client client() {
        return ClientBuilder.newClient();
    }

    @Override
    protected void configure() {
        // Use handler-based CORS (CrossOriginHandler). Jetty filter is deprecated.
        CorsFactory.registerHandlerIfEnabled(configuration(), environment());
    }
}
