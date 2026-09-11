package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.bi.AnalyticsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;

/**
 * Orchestrates the OAuth token and revocation grants (RFC 6749 §4.1.3, §6 / RFC 7009): grant-type dispatch,
 * per-grant parameter validation, client resolution, and translation of lower-level failures into the OAuth
 * error envelope. Every rejection is raised as an {@link OAuthException} so the resource stays thin and the
 * error response is assembled in one place ({@code OAuthExceptionMapper}).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthTokenService {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull McpOAuthService mcpOAuthService;
    private final @NonNull AnalyticsService analyticsService;

    public TokenResponse issueToken(String grantType, String code, String redirectUri, String clientId,
            String codeVerifier, String refreshToken) {
        if (GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            return issueFromAuthorizationCode(code, redirectUri, clientId, codeVerifier);
        }
        if (GRANT_REFRESH_TOKEN.equals(grantType)) {
            return issueFromRefreshToken(refreshToken, clientId);
        }
        if (StringUtils.isBlank(grantType)) {
            log.warn("MCP OAuth token request rejected: missing grant_type '{}'", clientId);
            throw new OAuthException(ERROR_INVALID_REQUEST);
        }
        log.warn("MCP OAuth token request rejected: unsupported grant_type '{}', '{}'",
                grantType, clientId);
        throw new OAuthException(ERROR_UNSUPPORTED_GRANT_TYPE);
    }

    /**
     * Best-effort revocation (RFC 7009 §2.2): the endpoint always reports success, so any failure is logged
     * and swallowed rather than surfaced to the caller.
     */
    public void revoke(@NonNull String token) {
        try {
            mcpOAuthService.revoke(token);
        } catch (Exception e) {
            log.warn("MCP OAuth revoke failed", e);
        }
    }

    private TokenResponse issueFromAuthorizationCode(String code, String redirectUri, String clientId,
            String codeVerifier) {
        if (StringUtils.isBlank(code) || StringUtils.isBlank(redirectUri) || StringUtils.isBlank(clientId)
                || StringUtils.isBlank(codeVerifier)) {
            log.warn("MCP OAuth authorization_code request rejected: missing required parameters '{}'",
                    clientId);
            throw new OAuthException(ERROR_INVALID_REQUEST);
        }
        McpOAuthClient client = clientService.resolve(clientId)
                .orElseThrow(() -> {
                    log.warn("MCP OAuth authorization_code request rejected: unknown client '{}'", clientId);
                    return new OAuthException(ERROR_INVALID_CLIENT);
                });
        try {
            CodeExchange exchange = mcpOAuthService.exchangeCode(code, codeVerifier, redirectUri, client);
            log.info("MCP OAuth authorization_code exchanged '{}'", clientId);

            if (exchange.firstConnection()) {
                // This endpoint is unauthenticated, so RequestContext carries no user and the 2-arg overload
                // would silently fall back to the installation anonymous ID. Pass the resource owner
                // explicitly: the warehouse joins this event on the user_name the frontend identifies on.
                analyticsService.trackEvent("opik_mcp_connected", Map.of(
                        "user_name", exchange.userName(),
                        "workspace_id", exchange.tokens().workspaceId(),
                        "workspace_name", exchange.tokens().workspaceName(),
                        "client_id", clientId,
                        "client_name", client.name()),
                        exchange.userName());
            }

            return exchange.tokens();
        } catch (BadRequestException e) {
            log.warn("MCP OAuth authorization_code exchange failed '{}'", clientId, e);
            throw new OAuthException(ERROR_INVALID_GRANT);
        }
    }

    private TokenResponse issueFromRefreshToken(String refreshToken, String clientId) {
        if (StringUtils.isBlank(refreshToken) || StringUtils.isBlank(clientId)) {
            log.warn("MCP OAuth refresh_token request rejected: missing required parameters '{}'", clientId);
            throw new OAuthException(ERROR_INVALID_REQUEST);
        }
        if (clientService.resolve(clientId).isEmpty()) {
            log.warn("MCP OAuth refresh_token request rejected: unknown client '{}'", clientId);
            throw new OAuthException(ERROR_INVALID_CLIENT);
        }
        try {
            TokenResponse tokens = mcpOAuthService.refresh(refreshToken, clientId);
            log.info("MCP OAuth refresh_token rotated '{}'", clientId);
            return tokens;
        } catch (BadRequestException e) {
            log.warn("MCP OAuth refresh failed '{}'", clientId, e);
            throw new OAuthException(ERROR_INVALID_GRANT);
        }
    }
}
