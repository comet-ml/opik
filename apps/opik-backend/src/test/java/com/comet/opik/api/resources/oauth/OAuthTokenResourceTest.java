package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.ACCESS_PREFIX;
import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.REFRESH_PREFIX;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Token Resource Test")
class OAuthTokenResourceTest {

    private static final String CLIENT_ID = "client-123";
    private static final String REDIRECT_URI = "http://localhost:1234/cb";
    private static final String CODE = "auth-code-xyz";
    private static final String CODE_VERIFIER = "verifier";
    private static final String REFRESH_TOKEN = REFRESH_PREFIX + "abc";

    @Mock
    private OAuthClientService clientService;
    @Mock
    private McpOAuthService mcpOAuthService;

    @InjectMocks
    private OAuthTokenResource resource;

    private McpOAuthClient validClient() {
        return McpOAuthClient.builder().clientId(CLIENT_ID).name("c").redirectUris(Set.of(REDIRECT_URI)).build();
    }

    private TokenResponse minted() {
        return new TokenResponse(ACCESS_PREFIX + "xxx", REFRESH_PREFIX + "yyy", "Bearer", 3600L, "ws-1", "default");
    }

    private static void assertError(Response response, String expectedCode) {
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(response.getEntity()).isInstanceOf(OAuthError.class);
        assertThat(((OAuthError) response.getEntity()).error()).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("POST /token (authorization_code): valid request returns full token response")
    void token_authCodeGrant_returnsTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(CODE, CODE_VERIFIER, REDIRECT_URI, CLIENT_ID)).thenReturn(minted());

        Response response = resource.token("authorization_code", CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(response.getEntity()).isEqualTo(minted());
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
        Response response = resource.token("authorization_code", code, redirectUri, clientId, codeVerifier, null);

        assertError(response, ERROR_INVALID_REQUEST);
        verify(clientService, never()).resolve(any());
    }

    @Test
    @DisplayName("POST /token (authorization_code): unknown client_id → invalid_client")
    void token_authCodeGrant_unknownClient_invalidClient() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.empty());

        Response response = resource.token("authorization_code", CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertError(response, ERROR_INVALID_CLIENT);
        verify(mcpOAuthService, never()).exchangeCode(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /token (authorization_code): exchange failure surfaces as invalid_grant")
    void token_authCodeGrant_exchangeFails_returnsInvalidGrant() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(any(), any(), any(), any()))
                .thenThrow(new BadRequestException(ERROR_INVALID_GRANT));

        Response response = resource.token("authorization_code", CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertError(response, ERROR_INVALID_GRANT);
    }

    @Test
    @DisplayName("POST /token (refresh_token): rotates and returns full token response")
    void token_refreshGrant_rotatesTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(REFRESH_TOKEN, CLIENT_ID)).thenReturn(minted());

        Response response = resource.token("refresh_token", null, null, CLIENT_ID, null, REFRESH_TOKEN);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(response.getEntity()).isEqualTo(minted());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,   client-123",
            "rt-abc, NULL",
    })
    @DisplayName("POST /token (refresh_token): each missing required field → invalid_request")
    void token_refreshGrant_missingFields_invalidRequest(String refreshToken, String clientId) {
        Response response = resource.token("refresh_token", null, null, clientId, null, refreshToken);

        assertError(response, ERROR_INVALID_REQUEST);
        verify(clientService, never()).resolve(any());
        verify(mcpOAuthService, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("POST /token: unsupported grant_type → unsupported_grant_type")
    void token_unsupportedGrant_returnsUnsupportedGrantType() {
        Response response = resource.token("client_credentials", null, null, CLIENT_ID, null, null);

        assertError(response, ERROR_UNSUPPORTED_GRANT_TYPE);
    }

    @Test
    @DisplayName("POST /token: blank grant_type → invalid_request")
    void token_blankGrant_returnsInvalidRequest() {
        Response response = resource.token(null, null, null, CLIENT_ID, null, null);

        assertError(response, ERROR_INVALID_REQUEST);
    }

    @Test
    @DisplayName("POST /revoke: valid token returns 200 OK and revokes")
    void revoke_validToken_returnsOk() {
        Response response = resource.revoke(ACCESS_PREFIX + "xxx", "access_token", CLIENT_ID);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        verify(mcpOAuthService).revoke(ACCESS_PREFIX + "xxx");
    }

    @Test
    @DisplayName("POST /revoke: per RFC 7009 §2.2, returns 200 even when service throws")
    void revoke_serviceThrows_stillReturnsOk() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(mcpOAuthService).revoke(any());

        Response response = resource.revoke(ACCESS_PREFIX + "zzz", null, CLIENT_ID);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }

    @Test
    @DisplayName("POST /revoke: blank token short-circuits to 200 without hitting the service")
    void revoke_blankToken_skipsService() {
        Response response = resource.revoke("  ", null, CLIENT_ID);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        verify(mcpOAuthService, never()).revoke(any());
    }
}
