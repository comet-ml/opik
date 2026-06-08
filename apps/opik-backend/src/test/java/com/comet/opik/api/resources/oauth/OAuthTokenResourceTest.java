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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.ACCESS_PREFIX;
import static com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils.REFRESH_PREFIX;
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

    @Test
    @DisplayName("POST /token (authorization_code): valid request returns access+refresh tokens")
    void token_authCodeGrant_returnsTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(CODE, CODE_VERIFIER, REDIRECT_URI, CLIENT_ID)).thenReturn(minted());

        Response response = resource.token("authorization_code", CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        TokenResponse body = (TokenResponse) response.getEntity();
        assertThat(body.accessToken()).startsWith(ACCESS_PREFIX);
        assertThat(body.refreshToken()).startsWith(REFRESH_PREFIX);
        assertThat(body.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("POST /token (authorization_code): missing fields → invalid_request")
    void token_authCodeGrant_missingFields_invalidRequest() {
        Response response = resource.token("authorization_code", null, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        verify(clientService, never()).resolve(any());
    }

    @Test
    @DisplayName("POST /token (authorization_code): unknown client_id → invalid_client")
    void token_authCodeGrant_unknownClient_invalidClient() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.empty());

        Response response = resource.token("authorization_code", CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        verify(mcpOAuthService, never()).exchangeCode(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /token (authorization_code): exchange failure surfaces as 400 with error")
    void token_authCodeGrant_exchangeFails_returnsError() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(any(), any(), any(), any()))
                .thenThrow(new BadRequestException("invalid_grant"));

        Response response = resource.token("authorization_code", CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    @DisplayName("POST /token (refresh_token): rotates and returns new access+refresh tokens")
    void token_refreshGrant_rotatesTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(REFRESH_TOKEN, CLIENT_ID)).thenReturn(minted());

        Response response = resource.token("refresh_token", null, null, CLIENT_ID, null, REFRESH_TOKEN);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        TokenResponse body = (TokenResponse) response.getEntity();
        assertThat(body.refreshToken()).isEqualTo(REFRESH_PREFIX + "yyy");
    }

    @Test
    @DisplayName("POST /token: unsupported grant_type → unsupported_grant_type")
    void token_unsupportedGrant_returnsError() {
        Response response = resource.token("client_credentials", null, null, CLIENT_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
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
