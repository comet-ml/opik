package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyBundle;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyEnvironment;

/**
 * Toggles the MCP OAuth resources based on {@code mcpOAuth.enabled}.
 * <p>
 * The flag is checked here rather than gating bundle registration in {@code OpikApplication} because the parsed
 * {@link OpikConfiguration} is only available once Dropwizard reaches the run phase. Bundles are registered during
 * {@code initialize(Bootstrap)}, where no configuration exists yet, so the condition can only be evaluated inside a
 * {@link GuiceyBundle#run(GuiceyEnvironment)} callback. The resources are auto-discovered and disabled when the flag is
 * off, which is the guicey-idiomatic way to express a config-conditional extension.
 */
public class McpOAuthBundle implements GuiceyBundle {

    @Override
    public void run(GuiceyEnvironment environment) {
        OpikConfiguration config = environment.configuration();
        if (!config.getMcpOAuth().isEnabled()) {
            environment.disableExtensions(
                    OAuthMetadataResource.class,
                    OAuthAuthorizeResource.class,
                    OAuthTokenResource.class);
        }
    }
}
