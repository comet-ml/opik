package com.comet.opik.domain.mcpoauth;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Token Service Test")
class OAuthTokenServiceTest {

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
    private OAuthTokenService service;

    private McpOAuthClient validClient() {
        return McpOAuthClient.builder().id(CLIENT_ID).name("c").redirectUris(Set.of(REDIRECT_URI)).build();
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

    private void assertOAuthError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(OAuthException.class)
                .satisfies(e -> assertThat(((OAuthException) e).getError()).isEqualTo(expectedCode));
    }

    @Test
    @DisplayName("authorization_code: valid request returns the exchanged tokens")
    void issueToken_authCodeGrant_returnsTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(CODE, CODE_VERIFIER, REDIRECT_URI, CLIENT_ID)).thenReturn(minted());

        TokenResponse response = service.issueToken(GRANT_AUTHORIZATION_CODE, CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null);

        assertThat(response).isEqualTo(minted());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,          http://localhost:1234/cb, client-123, verifier",
            "auth-code-xyz, NULL,                     client-123, verifier",
            "auth-code-xyz, http://localhost:1234/cb, NULL,       verifier",
            "auth-code-xyz, http://localhost:1234/cb, client-123, NULL",
    })
    @DisplayName("authorization_code: each missing required field → invalid_request")
    void issueToken_authCodeGrant_missingFields_invalidRequest(String code, String redirectUri, String clientId,
            String codeVerifier) {
        assertOAuthError(() -> service.issueToken(GRANT_AUTHORIZATION_CODE, code, redirectUri, clientId, codeVerifier,
                null), ERROR_INVALID_REQUEST);

        verify(clientService, never()).resolve(any());
    }

    @Test
    @DisplayName("authorization_code: unknown client_id → invalid_client")
    void issueToken_authCodeGrant_unknownClient_invalidClient() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.empty());

        assertOAuthError(() -> service.issueToken(GRANT_AUTHORIZATION_CODE, CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null), ERROR_INVALID_CLIENT);

        verify(mcpOAuthService, never()).exchangeCode(any(), any(), any(), any());
    }

    @Test
    @DisplayName("authorization_code: exchange failure is translated to invalid_grant")
    void issueToken_authCodeGrant_exchangeFails_invalidGrant() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(any(), any(), any(), any()))
                .thenThrow(new BadRequestException(ERROR_INVALID_GRANT));

        assertOAuthError(() -> service.issueToken(GRANT_AUTHORIZATION_CODE, CODE, REDIRECT_URI, CLIENT_ID,
                CODE_VERIFIER, null), ERROR_INVALID_GRANT);
    }

    @Test
    @DisplayName("refresh_token: valid request returns the rotated tokens")
    void issueToken_refreshGrant_returnsTokens() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(REFRESH_TOKEN, CLIENT_ID)).thenReturn(minted());

        TokenResponse response = service.issueToken(GRANT_REFRESH_TOKEN, null, null, CLIENT_ID, null, REFRESH_TOKEN);

        assertThat(response).isEqualTo(minted());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,   client-123",
            "rt-abc, NULL",
    })
    @DisplayName("refresh_token: each missing required field → invalid_request")
    void issueToken_refreshGrant_missingFields_invalidRequest(String refreshToken, String clientId) {
        assertOAuthError(() -> service.issueToken(GRANT_REFRESH_TOKEN, null, null, clientId, null, refreshToken),
                ERROR_INVALID_REQUEST);

        verify(clientService, never()).resolve(any());
        verify(mcpOAuthService, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("refresh_token: unknown client_id → invalid_client")
    void issueToken_refreshGrant_unknownClient_invalidClient() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.empty());

        assertOAuthError(() -> service.issueToken(GRANT_REFRESH_TOKEN, null, null, CLIENT_ID, null, REFRESH_TOKEN),
                ERROR_INVALID_CLIENT);

        verify(mcpOAuthService, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("refresh_token: refresh failure is translated to invalid_grant")
    void issueToken_refreshGrant_refreshFails_invalidGrant() {
        when(clientService.resolve(CLIENT_ID)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(any(), any())).thenThrow(new BadRequestException(ERROR_INVALID_GRANT));

        assertOAuthError(() -> service.issueToken(GRANT_REFRESH_TOKEN, null, null, CLIENT_ID, null, REFRESH_TOKEN),
                ERROR_INVALID_GRANT);
    }

    @Test
    @DisplayName("unsupported grant_type → unsupported_grant_type")
    void issueToken_unsupportedGrant_unsupportedGrantType() {
        assertOAuthError(() -> service.issueToken("client_credentials", null, null, CLIENT_ID, null, null),
                ERROR_UNSUPPORTED_GRANT_TYPE);
    }

    @Test
    @DisplayName("missing grant_type → invalid_request")
    void issueToken_blankGrant_invalidRequest() {
        assertOAuthError(() -> service.issueToken(null, null, null, CLIENT_ID, null, null), ERROR_INVALID_REQUEST);
    }

    @Test
    @DisplayName("revoke: delegates to the underlying service")
    void revoke_delegates() {
        String accessToken = ACCESS_PREFIX + "xxx";

        service.revoke(accessToken);

        verify(mcpOAuthService).revoke(accessToken);
    }

    @Test
    @DisplayName("revoke: per RFC 7009 §2.2, swallows underlying failures")
    void revoke_serviceThrows_isSwallowed() {
        Mockito.doThrow(new RuntimeException("db down")).when(mcpOAuthService).revoke(any());

        service.revoke(ACCESS_PREFIX + "zzz");

        verify(mcpOAuthService).revoke(any());
    }
}
