package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.OAuthException;
import com.comet.opik.domain.mcpoauth.OAuthTokenService;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.ACCESS_PREFIX;
import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.REFRESH_PREFIX;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REVOKE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuth Token Resource Test")
class OAuthTokenResourceTest {

    private static final String CLIENT_ID = "client-123";

    private static final OAuthTokenService tokenService = mock(OAuthTokenService.class);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new OAuthTokenResource(tokenService))
            .addProvider(OAuthExceptionMapper.class)
            .build();

    @BeforeEach
    void setUp() {
        reset(tokenService);
    }

    private TokenResponse minted() {
        return TokenResponse.builder()
                .accessToken(ACCESS_PREFIX + "xxx")
                .refreshToken(REFRESH_PREFIX + "yyy")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .workspaceId("ws-1")
                .workspaceName("default")
                .build();
    }

    private Form form(String... namesAndValues) {
        var form = new Form();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] != null) {
                form.param(namesAndValues[i], namesAndValues[i + 1]);
            }
        }
        return form;
    }

    private Response postToken(Form form) {
        return EXT.target(TOKEN_PATH).request().post(Entity.form(form));
    }

    private Response postRevoke(Form form) {
        return EXT.target(REVOKE_PATH).request().post(Entity.form(form));
    }

    private void assertNoStore(Response response) {
        assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaderString("Pragma")).isEqualTo("no-cache");
    }

    @Test
    @DisplayName("POST /token: delegates to the service and renders the token response (snake_case + no-store)")
    void token_success_rendersTokenResponse() {
        when(tokenService.issueToken(any(), any(), any(), any(), any(), any())).thenReturn(minted());

        try (Response response = postToken(form(PARAM_GRANT_TYPE, GRANT_AUTHORIZATION_CODE, PARAM_CLIENT_ID,
                CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertNoStore(response);

            response.bufferEntity();
            assertThat(response.readEntity(String.class))
                    .contains("\"access_token\"", "\"refresh_token\"", "\"token_type\"", "\"expires_in\"",
                            "\"workspace_id\"", "\"workspace_name\"");
            assertThat(response.readEntity(TokenResponse.class)).isEqualTo(minted());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ERROR_INVALID_REQUEST, ERROR_INVALID_CLIENT, ERROR_INVALID_GRANT,
            ERROR_UNSUPPORTED_GRANT_TYPE})
    @DisplayName("POST /token: an OAuthException is rendered as the RFC 6749 §5.2 error envelope")
    void token_serviceRejects_rendersOAuthError(String errorCode) {
        when(tokenService.issueToken(any(), any(), any(), any(), any(), any()))
                .thenThrow(new OAuthException(errorCode));

        try (Response response = postToken(form(PARAM_GRANT_TYPE, GRANT_AUTHORIZATION_CODE, PARAM_CLIENT_ID,
                CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
            assertThat(response.getMediaType().toString()).startsWith(MediaType.APPLICATION_JSON);
            assertNoStore(response);
            assertThat(response.readEntity(OAuthError.class).error()).isEqualTo(errorCode);
        }
    }

    @Test
    @DisplayName("POST /revoke: delegates a present token to the service and returns 200 OK")
    void revoke_presentToken_delegatesAndReturnsOk() {
        String accessToken = ACCESS_PREFIX + "xxx";

        try (Response response = postRevoke(form(PARAM_TOKEN, accessToken, PARAM_CLIENT_ID, CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
        verify(tokenService).revoke(accessToken);
    }

    @Test
    @DisplayName("POST /revoke: blank token short-circuits to 200 without hitting the service")
    void revoke_blankToken_skipsService() {
        try (Response response = postRevoke(form(PARAM_TOKEN, "   ", PARAM_CLIENT_ID, CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
        verify(tokenService, never()).revoke(any());
    }
}
