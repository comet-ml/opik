package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyEnvironment;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpOAuthBundle wiring")
class McpOAuthBundleTest {

    private final McpOAuthBundle bundle = new McpOAuthBundle();

    @Mock
    private GuiceyEnvironment environment;

    @ParameterizedTest(name = "enabled={0} → disableExtensions called {1} time(s)")
    @CsvSource({"false, 1", "true, 0"})
    @DisplayName("disables the MCP OAuth resources only when the feature is off")
    void togglesResourcesByFlag(boolean enabled, int disableInvocations) {
        var configuration = new OpikConfiguration();
        configuration.getMcpOAuth().setEnabled(enabled);
        when(environment.configuration()).thenReturn(configuration);

        bundle.run(environment);

        verify(environment, times(disableInvocations))
                .disableExtensions(
                        OAuthMetadataResource.class,
                        OAuthAuthorizeResource.class,
                        OAuthTokenResource.class,
                        OAuthRegisterResource.class);
    }
}
