package com.comet.opik.domain.mcpoauth;

import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Token Service Test")
class OAuthTokenServiceTest {

    private final String clientId = "client-" + RandomStringUtils.secure().nextAlphanumeric(8);
    private final String redirectUri = "http://localhost:1234/" + RandomStringUtils.secure().nextAlphanumeric(8);
    private final String code = RandomStringUtils.secure().nextAlphanumeric(20);
    private final String codeVerifier = RandomStringUtils.secure().nextAlphanumeric(20);
    private final String refreshToken = REFRESH_PREFIX + RandomStringUtils.secure().nextAlphanumeric(10);

    private final TokenResponse minted = TokenResponse.builder()
            .accessToken(ACCESS_PREFIX + RandomStringUtils.secure().nextAlphanumeric(20))
            .refreshToken(REFRESH_PREFIX + RandomStringUtils.secure().nextAlphanumeric(20))
            .tokenType("Bearer")
            .expiresIn(RandomUtils.secure().randomLong(0, 86400))
            .workspaceId(RandomStringUtils.secure().nextAlphanumeric(10))
            .workspaceName(RandomStringUtils.secure().nextAlphanumeric(10))
            .build();

    @Mock
    private OAuthClientService clientService;
    @Mock
    private McpOAuthService mcpOAuthService;

    @InjectMocks
    private OAuthTokenService service;

    private McpOAuthClient validClient() {
        return McpOAuthClient.builder()
                .id(clientId)
                .name(RandomStringUtils.secure().nextAlphanumeric(5))
                .redirectUris(Set.of(redirectUri))
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
        when(clientService.resolve(clientId)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(code, codeVerifier, redirectUri, clientId)).thenReturn(minted);

        TokenResponse response = service.issueToken(GRANT_AUTHORIZATION_CODE, code, redirectUri, clientId,
                codeVerifier, null);

        assertThat(response).isEqualTo(minted);
    }

    private static Stream<Arguments> issueToken_authCodeGrant_missingFields_invalidRequest() {
        String code = RandomStringUtils.secure().nextAlphanumeric(20);
        String redirectUri = "http://localhost:1234/" + RandomStringUtils.secure().nextAlphanumeric(8);
        String clientId = "client-" + RandomStringUtils.secure().nextAlphanumeric(8);
        String codeVerifier = RandomStringUtils.secure().nextAlphanumeric(20);
        return Stream.of(
                arguments(null, redirectUri, clientId, codeVerifier),
                arguments(code, null, clientId, codeVerifier),
                arguments(code, redirectUri, null, codeVerifier),
                arguments(code, redirectUri, clientId, null));
    }

    @ParameterizedTest
    @MethodSource
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
        when(clientService.resolve(clientId)).thenReturn(Optional.empty());

        assertOAuthError(() -> service.issueToken(GRANT_AUTHORIZATION_CODE, code, redirectUri, clientId,
                codeVerifier, null), ERROR_INVALID_CLIENT);

        verify(mcpOAuthService, never()).exchangeCode(any(), any(), any(), any());
    }

    @Test
    @DisplayName("authorization_code: exchange failure is translated to invalid_grant")
    void issueToken_authCodeGrant_exchangeFails_invalidGrant() {
        when(clientService.resolve(clientId)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.exchangeCode(any(), any(), any(), any()))
                .thenThrow(new BadRequestException(ERROR_INVALID_GRANT));

        assertOAuthError(() -> service.issueToken(GRANT_AUTHORIZATION_CODE, code, redirectUri, clientId,
                codeVerifier, null), ERROR_INVALID_GRANT);
    }

    @Test
    @DisplayName("refresh_token: valid request returns the rotated tokens")
    void issueToken_refreshGrant_returnsTokens() {
        when(clientService.resolve(clientId)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(refreshToken, clientId)).thenReturn(minted);

        TokenResponse response = service.issueToken(GRANT_REFRESH_TOKEN, null, null, clientId, null, refreshToken);

        assertThat(response).isEqualTo(minted);
    }

    private static Stream<Arguments> issueToken_refreshGrant_missingFields_invalidRequest() {
        String refreshToken = REFRESH_PREFIX + RandomStringUtils.secure().nextAlphanumeric(10);
        String clientId = "client-" + RandomStringUtils.secure().nextAlphanumeric(8);
        return Stream.of(
                arguments(null, clientId),
                arguments(refreshToken, null));
    }

    @ParameterizedTest
    @MethodSource
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
        when(clientService.resolve(clientId)).thenReturn(Optional.empty());

        assertOAuthError(() -> service.issueToken(GRANT_REFRESH_TOKEN, null, null, clientId, null, refreshToken),
                ERROR_INVALID_CLIENT);

        verify(mcpOAuthService, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("refresh_token: refresh failure is translated to invalid_grant")
    void issueToken_refreshGrant_refreshFails_invalidGrant() {
        when(clientService.resolve(clientId)).thenReturn(Optional.of(validClient()));
        when(mcpOAuthService.refresh(any(), any())).thenThrow(new BadRequestException(ERROR_INVALID_GRANT));

        assertOAuthError(() -> service.issueToken(GRANT_REFRESH_TOKEN, null, null, clientId, null, refreshToken),
                ERROR_INVALID_GRANT);
    }

    @Test
    @DisplayName("unsupported grant_type → unsupported_grant_type")
    void issueToken_unsupportedGrant_unsupportedGrantType() {
        assertOAuthError(() -> service.issueToken("client_credentials", null, null, clientId, null, null),
                ERROR_UNSUPPORTED_GRANT_TYPE);
    }

    @Test
    @DisplayName("missing grant_type → invalid_request")
    void issueToken_blankGrant_invalidRequest() {
        assertOAuthError(() -> service.issueToken(null, null, null, clientId, null, null), ERROR_INVALID_REQUEST);
    }

    @Test
    @DisplayName("revoke: delegates to the underlying service")
    void revoke_delegates() {
        String accessToken = ACCESS_PREFIX + RandomStringUtils.secure().nextAlphanumeric(20);

        service.revoke(accessToken);

        verify(mcpOAuthService).revoke(accessToken);
    }

    @Test
    @DisplayName("revoke: per RFC 7009 §2.2, swallows underlying failures")
    void revoke_serviceThrows_isSwallowed() {
        Mockito.doThrow(new RuntimeException("db down")).when(mcpOAuthService).revoke(any());

        service.revoke(ACCESS_PREFIX + RandomStringUtils.secure().nextAlphanumeric(20));

        verify(mcpOAuthService).revoke(any());
    }
}
