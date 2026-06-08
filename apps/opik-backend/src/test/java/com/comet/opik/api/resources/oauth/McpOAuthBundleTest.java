package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyEnvironment;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpOAuthBundle wiring")
class McpOAuthBundleTest {

    private final McpOAuthBundle bundle = new McpOAuthBundle();

    @Mock
    private GuiceyEnvironment environment;

    @Mock
    private OpikConfiguration configuration;

    @Mock
    private McpOAuthConfig mcpOAuthConfig;

    @Test
    @DisplayName("disables all MCP OAuth resources when MCP OAuth is off")
    void disablesResourcesWhenDisabled() {
        when(environment.configuration()).thenReturn(configuration);
        when(configuration.getMcpOAuth()).thenReturn(mcpOAuthConfig);
        when(mcpOAuthConfig.isEnabled()).thenReturn(false);

        bundle.run(environment);

        verify(environment).disableExtensions(
                OAuthMetadataResource.class,
                OAuthAuthorizeResource.class,
                OAuthTokenResource.class,
                OAuthRegisterResource.class);
    }

    @Test
    @DisplayName("keeps the MCP OAuth resources when MCP OAuth is on")
    void keepsResourcesWhenEnabled() {
        when(environment.configuration()).thenReturn(configuration);
        when(configuration.getMcpOAuth()).thenReturn(mcpOAuthConfig);
        when(mcpOAuthConfig.isEnabled()).thenReturn(true);

        bundle.run(environment);

        verify(environment, never()).disableExtensions(
                OAuthMetadataResource.class,
                OAuthAuthorizeResource.class,
                OAuthTokenResource.class,
                OAuthRegisterResource.class);
    }
}
