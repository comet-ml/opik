package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.grizzly.connector.GrizzlyConnectorProvider;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

@UtilityClass
public class ClientSupportUtils {

    /**
     * Not using GrizzlyConnectorProvider for multipart support as it doesn't properly
     * handle multipart Content-Type headers. Uses the default HttpUrlConnector instead.
     */
    public void configMultiPartFeature(ClientSupport client) {
        configCore(client);
        client.getClient().register(MultiPartFeature.class);
    }

    public void config(ClientSupport client) {
        configCore(client);
        client.getClient().getConfiguration().connectorProvider(new GrizzlyConnectorProvider()); // Required for PATCH:
        // https://github.com/dropwizard/dropwizard/discussions/6431/ Required for PATCH:
    }

    private void configCore(ClientSupport client) {
        client.getClient().register(new ConditionalGZipFilter());
        client.getClient().getConfiguration().property(ClientProperties.READ_TIMEOUT, 35_000);
    }
}
