package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuth Validate Token Resource Test")
class OAuthValidateTokenResourceTest {

    private static final String ACCESS_TOKEN = McpOAuthTokenUtils.ACCESS_PREFIX
            + RandomStringUtils.secure().nextAlphanumeric(24);

    private static final McpOAuthService mcpOAuthService = mock(McpOAuthService.class);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new OAuthValidateTokenResource(mcpOAuthService))
            .build();

    @BeforeEach
    void setUp() {
        reset(mcpOAuthService);
    }

    private Response validate(String authHeader) {
        var request = EXT.target(OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH).request();
        if (authHeader != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return request.post(Entity.json(""));
    }

    @ParameterizedTest
    @NullSource // missing Authorization header
    @ValueSource(strings = {
            "", // empty
            "opik_mcp_at_no-scheme", // bare token, no Bearer scheme
            "Basic " + "opik_mcp_at_live-token", // wrong scheme
            "Bearer opik_mcp_rt_refresh-token", // refresh token, not an access token
            "Bearer not-an-opik-token" // unknown token shape
    })
    @DisplayName("rejects anything that is not a Bearer access token without touching the service")
    void rejectsNonBearerAccessToken(String authHeader) {
        try (Response response = validate(authHeader)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        }

        verify(mcpOAuthService, never()).validateAccessToken(any());
    }

    @Test
    @DisplayName("rejects a well-formed access token the service cannot validate")
    void rejectsUnvalidatedAccessToken() {
        when(mcpOAuthService.validateAccessToken(ACCESS_TOKEN)).thenReturn(Optional.empty());

        try (Response response = validate("Bearer " + ACCESS_TOKEN)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "bearer ", "BEARER "})
    @DisplayName("accepts a valid token and returns the validated identity, case-insensitively on the scheme")
    void acceptsValidToken(String schemePrefix) {
        var validated = ValidatedToken.builder()
                .userName(RandomStringUtils.secure().nextAlphanumeric(10))
                .workspaceId(UUID.randomUUID().toString())
                .workspaceName(RandomStringUtils.secure().nextAlphanumeric(10))
                .resource("http://localhost/api/v1/mcp/%s".formatted(RandomStringUtils.secure().nextAlphanumeric(8)))
                .build();
        when(mcpOAuthService.validateAccessToken(ACCESS_TOKEN)).thenReturn(Optional.of(validated));

        try (Response response = validate(schemePrefix + ACCESS_TOKEN)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.readEntity(ValidatedToken.class)).isEqualTo(validated);
        }
    }
}
