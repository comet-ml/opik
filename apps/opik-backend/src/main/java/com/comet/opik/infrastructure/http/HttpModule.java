package com.comet.opik.infrastructure.http;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

public class HttpModule extends AbstractModule {

    @Provides
    @Singleton
    public Client client() {
        return ClientBuilder.newClient();
    }

}
