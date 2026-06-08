package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthMetadataResource")
class OAuthMetadataResourceTest {

    @Mock
    private OpikConfiguration opikConfig;

    @Test
    @DisplayName("advertises RFC 8414 metadata derived from the configured issuer")
    void metadataAdvertisesEndpointsDerivedFromIssuer() {
        var mcpOAuthConfig = new McpOAuthConfig();
        String baseUrl = "https://opik.example.com/";
        mcpOAuthConfig.setBaseUrl(baseUrl);
        when(opikConfig.getMcpOAuth()).thenReturn(mcpOAuthConfig);

        var resource = new OAuthMetadataResource(opikConfig);

        try (Response response = resource.metadata()) {
            assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());

            var metadata = (AuthorizationServerMetadata) response.getEntity();
            assertThat(metadata.issuer()).isEqualTo(baseUrl);
            assertThat(metadata.authorizationEndpoint()).isEqualTo(baseUrl + "oauth/authorize");
            assertThat(metadata.tokenEndpoint()).isEqualTo(baseUrl + "oauth/token");
            assertThat(metadata.revocationEndpoint()).isEqualTo(baseUrl + "oauth/revoke");
            assertThat(metadata.registrationEndpoint()).isEqualTo(baseUrl + "oauth/register");
            assertThat(metadata.responseTypesSupported()).containsExactly("code");
            assertThat(metadata.grantTypesSupported()).containsExactly("authorization_code", "refresh_token");
            assertThat(metadata.codeChallengeMethodsSupported()).containsExactly("S256");
            assertThat(metadata.tokenEndpointAuthMethodsSupported()).containsExactly("none");
        }
    }
}
