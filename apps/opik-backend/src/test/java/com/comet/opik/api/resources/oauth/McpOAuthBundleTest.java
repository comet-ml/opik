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
    @DisplayName("disables the metadata resource when MCP OAuth is off")
    void disablesMetadataResourceWhenDisabled() {
        when(environment.configuration()).thenReturn(configuration);
        when(configuration.getMcpOAuth()).thenReturn(mcpOAuthConfig);
        when(mcpOAuthConfig.isEnabled()).thenReturn(false);

        bundle.run(environment);

        verify(environment).disableExtensions(OAuthMetadataResource.class);
    }

    @Test
    @DisplayName("keeps the metadata resource when MCP OAuth is on")
    void keepsMetadataResourceWhenEnabled() {
        when(environment.configuration()).thenReturn(configuration);
        when(configuration.getMcpOAuth()).thenReturn(mcpOAuthConfig);
        when(mcpOAuthConfig.isEnabled()).thenReturn(true);

        bundle.run(environment);

        verify(environment, never()).disableExtensions(OAuthMetadataResource.class);
    }
}
