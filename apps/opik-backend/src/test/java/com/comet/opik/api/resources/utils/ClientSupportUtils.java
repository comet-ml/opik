package com.comet.opik.api.resources.utils;

import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.grizzly.connector.GrizzlyConnectorProvider;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

public class ClientSupportUtils {

    private ClientSupportUtils() {
    }

    public static void config(ClientSupport client) {
        client.getClient().register(new ConditionalGZipFilter());

        client.getClient().getConfiguration().property(ClientProperties.READ_TIMEOUT, 35_000);
        client.getClient().getConfiguration().connectorProvider(new GrizzlyConnectorProvider()); // Required for PATCH:
        // https://github.com/dropwizard/dropwizard/discussions/6431/ Required for PATCH:
    }
}
