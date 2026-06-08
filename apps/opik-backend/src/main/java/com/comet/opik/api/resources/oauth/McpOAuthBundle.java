package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyBundle;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyEnvironment;

public class McpOAuthBundle implements GuiceyBundle {

    @Override
    public void run(GuiceyEnvironment environment) {
        OpikConfiguration config = environment.configuration();
        if (!config.getMcpOAuth().isEnabled()) {
            environment.disableExtensions(
                    OAuthMetadataResource.class,
                    OAuthAuthorizeResource.class,
                    OAuthTokenResource.class,
                    OAuthRegisterResource.class);
        }
    }
}
