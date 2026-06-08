package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthValidateResource")
class OAuthValidateResourceTest {

    private static final String ACCESS_TOKEN = McpOAuthTokenUtils.ACCESS_PREFIX + "live-token";

    @Mock
    private McpOAuthService mcpOAuthService;

    private OAuthValidateResource resource() {
        return new OAuthValidateResource(mcpOAuthService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", // blank
            "opik_mcp_at_no-scheme", // bare token, no Bearer scheme
            "Basic " + "opik_mcp_at_live-token", // wrong scheme
            "Bearer opik_mcp_rt_refresh-token", // refresh token, not an access token
            "Bearer not-an-opik-token" // unknown token shape
    })
    @DisplayName("rejects anything that is not a Bearer access token without touching the service")
    void rejectsNonBearerAccessToken(String authHeader) {
        assertThatThrownBy(() -> resource().validate(authHeader))
                .isInstanceOf(NotAuthorizedException.class);

        verify(mcpOAuthService, never()).validateAccessToken(any());
    }

    @Test
    @DisplayName("rejects a null Authorization header")
    void rejectsNullHeader() {
        assertThatThrownBy(() -> resource().validate(null))
                .isInstanceOf(NotAuthorizedException.class);

        verify(mcpOAuthService, never()).validateAccessToken(any());
    }

    @Test
    @DisplayName("rejects a well-formed access token the service cannot validate")
    void rejectsUnvalidatedAccessToken() {
        when(mcpOAuthService.validateAccessToken(ACCESS_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resource().validate("Bearer " + ACCESS_TOKEN))
                .isInstanceOf(NotAuthorizedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "bearer ", "BEARER "})
    @DisplayName("accepts a valid token and returns the validated identity, case-insensitively on the scheme")
    void acceptsValidToken(String schemePrefix) {
        var validated = ValidatedToken.builder()
                .userName("alice")
                .workspaceId("ws-1")
                .workspaceName("default")
                .resource("http://localhost/api/v1/mcp")
                .build();
        when(mcpOAuthService.validateAccessToken(ACCESS_TOKEN)).thenReturn(Optional.of(validated));

        try (Response response = resource().validate(schemePrefix + ACCESS_TOKEN)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.getEntity()).isEqualTo(validated);
        }
    }
}
