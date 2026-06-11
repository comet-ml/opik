package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.BadRequestException;
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
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;
import java.util.Set;

import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.ACCESS_PREFIX;
import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.REFRESH_PREFIX;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_VERIFIER;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REVOKE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuth Token Resource Test")
class OAuthTokenResourceTest {

    private static final String CLIENT_ID = "client-123";
    private static final String REDIRECT_URI = "http://localhost:1234/cb";
    private static final String CODE = "auth-code-xyz";
    private static final String CODE_VERIFIER = "verifier";
    private static final String REFRESH_TOKEN = REFRESH_PREFIX + "abc";

    private static final OAuthClientService clientService = mock(OAuthClientService.class);
    private static final McpOAuthService mcpOAuthService = mock(McpOAuthService.class);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new OAuthTokenResource(clientService, mcpOAuthService))
            .build();

    @BeforeEach
    void setUp() {
        reset(clientService, mcpOAuthService);
    }

    private McpOAuthClient validClient() {
        return McpOAuthClient.builder().id(CLIENT_ID).name("c").redirectUris(Set.of(REDIRECT_URI)).build();
    }

    private TokenResponse minted() {
        return new TokenResponse(ACCESS_PREFIX + "xxx", REFRESH_PREFIX + "yyy", "Bearer", 3600L, "ws-1", "default");
    }

    private static Response postToken(Form form) {
        return EXT.target(TOKEN_PATH).request().post(Entity.form(form));
    }

    private static Response postRevoke(Form form) {
        return EXT.target(REVOKE_PATH).request().post(Entity.form(form));
    }

    private static Form authCodeForm(String code, String redirectUri, String clientId, String codeVerifier) {
        return form(PARAM_GRANT_TYPE, GRANT_AUTHORIZATION_CODE, PARAM_CODE, code, PARAM_REDIRECT_URI, redirectUri,
                PARAM_CLIENT_ID, clientId, PARAM_CODE_VERIFIER, codeVerifier);
    }

    private static Form refreshForm(String refreshToken, String clientId) {
        return form(PARAM_GRANT_TYPE, GRANT_REFRESH_TOKEN, PARAM_REFRESH_TOKEN, refreshToken,
                PARAM_CLIENT_ID, clientId);
    }

    /**
     * Builds a form from name/value pairs, omitting any pair whose value is {@code null} so the corresponding
     * {@code @FormParam} arrives absent — mirroring a client that simply leaves a field out of the request.
     */
    private static Form form(String... namesAndValues) {
        var form = new Form();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] != null) {
                form.param(namesAndValues[i], namesAndValues[i + 1]);
            }
        }
        return form;
    }

    private static void assertNoStore(Response response) {
        assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaderString("Pragma")).isEqualTo("no-cache");
    }

    private static void assertError(Response response, String expectedCode) {
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(response.getMediaType().toString()).startsWith(MediaType.APPLICATION_JSON);
        assertNoStore(response);
        assertThat(response.readEntity(OAuthError.class).error()).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("POST /token (authorization_code): valid request returns full token response, snake_case + no-store")
    void token_authCodeGrant_returnsTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(CODE, CODE_VERIFIER, REDIRECT_URI, CLIENT_ID)).thenReturn(minted());

        try (Response response = postToken(authCodeForm(CODE, REDIRECT_URI, CLIENT_ID, CODE_VERIFIER))) {
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
    @CsvSource(nullValues = "NULL", value = {
            "NULL,          http://localhost:1234/cb, client-123, verifier",
            "auth-code-xyz, NULL,                     client-123, verifier",
            "auth-code-xyz, http://localhost:1234/cb, NULL,       verifier",
            "auth-code-xyz, http://localhost:1234/cb, client-123, NULL",
    })
    @DisplayName("POST /token (authorization_code): each missing required field → invalid_request")
    void token_authCodeGrant_missingFields_invalidRequest(String code, String redirectUri, String clientId,
            String codeVerifier) {
        try (Response response = postToken(authCodeForm(code, redirectUri, clientId, codeVerifier))) {
            assertError(response, ERROR_INVALID_REQUEST);
        }
        verify(clientService, never()).resolve(any());
    }

    @Test
    @DisplayName("POST /token (authorization_code): unknown client_id → invalid_client")
    void token_authCodeGrant_unknownClient_invalidClient() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.empty());

        try (Response response = postToken(authCodeForm(CODE, REDIRECT_URI, CLIENT_ID, CODE_VERIFIER))) {
            assertError(response, ERROR_INVALID_CLIENT);
        }
        verify(mcpOAuthService, never()).exchangeCode(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /token (authorization_code): exchange failure surfaces as invalid_grant")
    void token_authCodeGrant_exchangeFails_returnsInvalidGrant() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(any(), any(), any(), any()))
                .thenThrow(new BadRequestException(ERROR_INVALID_GRANT));

        try (Response response = postToken(authCodeForm(CODE, REDIRECT_URI, CLIENT_ID, CODE_VERIFIER))) {
            assertError(response, ERROR_INVALID_GRANT);
        }
    }

    @Test
    @DisplayName("POST /token (refresh_token): rotates and returns full token response")
    void token_refreshGrant_rotatesTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(REFRESH_TOKEN, CLIENT_ID)).thenReturn(minted());

        try (Response response = postToken(refreshForm(REFRESH_TOKEN, CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertNoStore(response);
            assertThat(response.readEntity(TokenResponse.class)).isEqualTo(minted());
        }
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,   client-123",
            "rt-abc, NULL",
    })
    @DisplayName("POST /token (refresh_token): each missing required field → invalid_request")
    void token_refreshGrant_missingFields_invalidRequest(String refreshToken, String clientId) {
        try (Response response = postToken(refreshForm(refreshToken, clientId))) {
            assertError(response, ERROR_INVALID_REQUEST);
        }
        verify(clientService, never()).resolve(any());
        verify(mcpOAuthService, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("POST /token: unsupported grant_type → unsupported_grant_type")
    void token_unsupportedGrant_returnsUnsupportedGrantType() {
        try (Response response = postToken(form(PARAM_GRANT_TYPE, "client_credentials", PARAM_CLIENT_ID, CLIENT_ID))) {
            assertError(response, ERROR_UNSUPPORTED_GRANT_TYPE);
        }
    }

    @Test
    @DisplayName("POST /token: missing grant_type → invalid_request")
    void token_blankGrant_returnsInvalidRequest() {
        try (Response response = postToken(form(PARAM_CLIENT_ID, CLIENT_ID))) {
            assertError(response, ERROR_INVALID_REQUEST);
        }
    }

    @Test
    @DisplayName("POST /revoke: valid token returns 200 OK and revokes")
    void revoke_validToken_returnsOk() {
        String accessToken = ACCESS_PREFIX + "xxx";

        try (Response response = postRevoke(form(PARAM_TOKEN, accessToken, PARAM_CLIENT_ID, CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
        verify(mcpOAuthService).revoke(accessToken);
    }

    @Test
    @DisplayName("POST /revoke: per RFC 7009 §2.2, returns 200 even when service throws")
    void revoke_serviceThrows_stillReturnsOk() {
        doThrow(new RuntimeException("db down")).when(mcpOAuthService).revoke(any());

        try (Response response = postRevoke(form(PARAM_TOKEN, ACCESS_PREFIX + "zzz", PARAM_CLIENT_ID, CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    @DisplayName("POST /revoke: blank token short-circuits to 200 without hitting the service")
    void revoke_blankToken_skipsService() {
        try (Response response = postRevoke(form(PARAM_TOKEN, "   ", PARAM_CLIENT_ID, CLIENT_ID))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
        verify(mcpOAuthService, never()).revoke(any());
    }
}
