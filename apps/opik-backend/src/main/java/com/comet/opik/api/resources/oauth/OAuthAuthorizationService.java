package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.CreateOAuthCodeCommand;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.AuthService;
import com.comet.opik.infrastructure.auth.UserWorkspace;
import com.comet.opik.infrastructure.auth.WorkspaceInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.UriBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.List;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_TARGET;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE_METHOD;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_ERROR;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESOURCE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_STATE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthAuthorizationService {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull AuthService authService;
    private final @NonNull McpOAuthService mcpOAuthService;
    private final @NonNull OpikConfiguration opikConfig;

    /**
     * Validates the authorization request and resolves the next redirect target: the client {@code redirect_uri} with an
     * {@code error} for invalid requests, the login page when there is no active session, or the consent page otherwise.
     */
    public URI resolveAuthorizeRedirect(@NonNull AuthorizeRequest request, Cookie session) {
        McpOAuthConfig config = opikConfig.getMcpOAuth();
        clientService.resolveForRedirect(request.clientId(), request.redirectUri());

        if (!RESPONSE_TYPE_CODE.equals(request.responseType())) {
            log.info("Authorize request for client_id '{}' uses unsupported response_type", request.clientId());
            return errorRedirect(request.redirectUri(), ERROR_UNSUPPORTED_RESPONSE_TYPE, request.state());
        }
        if (StringUtils.isBlank(request.codeChallenge())
                || !CODE_CHALLENGE_METHOD_S256.equals(request.codeChallengeMethod())) {
            log.info("Authorize request for client_id '{}' has missing or unsupported PKCE challenge",
                    request.clientId());
            return errorRedirect(request.redirectUri(), ERROR_INVALID_REQUEST, request.state());
        }
        if (!config.getMcpResourceUri().equals(request.resource())) {
            log.info("Authorize request for client_id '{}' targets an unexpected resource", request.clientId());
            return errorRedirect(request.redirectUri(), ERROR_INVALID_TARGET, request.state());
        }

        try {
            authService.listEligibleWorkspaces(session);
        } catch (ClientErrorException e) {
            log.info("No active session for client_id '{}', redirecting to login", request.clientId());
            return loginRedirect(config, request.rawQuery());
        }

        log.info("Authorize request for client_id '{}' validated, redirecting to consent", request.clientId());
        return consentRedirect(config, request);
    }

    /**
     * Resolves the client and the eligible workspaces to render the consent screen, and mints a fresh CSRF token to be
     * bound to the consent submission.
     */
    public AuthorizeContext buildConsentContext(@NonNull String clientId, String redirectUri, Cookie session) {
        McpOAuthClient client = clientService.resolveForRedirect(clientId, redirectUri);
        List<WorkspaceInfo> workspaces = authService.listEligibleWorkspaces(session);
        log.info("Resolved {} eligible workspace(s) for consent of client_id '{}'", workspaces.size(), clientId);
        return AuthorizeContext.builder()
                .clientName(client.name())
                .clientLogoUri(client.logoUri())
                .workspaces(workspaces)
                .csrfToken(McpOAuthTokenUtils.randomToken())
                .build();
    }

    /**
     * Authorizes the consented workspace, issues an authorization code and assembles the client redirect target.
     */
    public ConsentResponse issueAuthorizationCode(@NonNull ConsentRequest request, Cookie session) {
        McpOAuthConfig config = opikConfig.getMcpOAuth();
        clientService.resolveForRedirect(request.clientId(), request.redirectUri());
        if (!config.getMcpResourceUri().equals(request.resource())) {
            log.info("Consent for client_id '{}' targets an unexpected resource", request.clientId());
            throw new BadRequestException(ERROR_INVALID_TARGET);
        }

        UserWorkspace workspace = authService.authorizeWorkspace(session, request.workspaceName());
        String code = mcpOAuthService.createAuthorizationCode(CreateOAuthCodeCommand.builder()
                .clientId(request.clientId())
                .userName(workspace.userName())
                .workspaceName(workspace.workspaceName())
                .workspaceId(workspace.workspaceId())
                .codeChallenge(request.codeChallenge())
                .redirectUri(request.redirectUri())
                .resource(request.resource())
                .build());
        log.info("Issued authorization code for client_id '{}' on workspace '{}'", request.clientId(),
                workspace.workspaceName());

        UriBuilder redirectTo = UriBuilder.fromUri(request.redirectUri()).queryParam(PARAM_CODE, code);
        if (StringUtils.isNotBlank(request.state())) {
            redirectTo.queryParam(PARAM_STATE, request.state());
        }
        return ConsentResponse.builder()
                .redirectTo(redirectTo.build().toString())
                .build();
    }

    private static URI errorRedirect(String redirectUri, String error, String state) {
        UriBuilder builder = UriBuilder.fromUri(redirectUri).queryParam(PARAM_ERROR, error);
        if (StringUtils.isNotBlank(state)) {
            builder.queryParam(PARAM_STATE, state);
        }
        return builder.build();
    }

    private static URI loginRedirect(McpOAuthConfig config, String rawQuery) {
        String authorizeUrl = config.getBaseUrl() + AUTHORIZE_PATH
                + (StringUtils.isBlank(rawQuery) ? "" : "?" + rawQuery);
        return UriBuilder.fromUri(config.getBaseUrl())
                .path("/login")
                .queryParam("returnTo", authorizeUrl)
                .build();
    }

    private static URI consentRedirect(McpOAuthConfig config, AuthorizeRequest request) {
        UriBuilder consentUri = UriBuilder.fromUri(config.getBaseUrl())
                .path("/oauth/consent")
                .queryParam(PARAM_CLIENT_ID, request.clientId())
                .queryParam(PARAM_REDIRECT_URI, request.redirectUri())
                .queryParam(PARAM_RESPONSE_TYPE, request.responseType())
                .queryParam(PARAM_CODE_CHALLENGE, request.codeChallenge())
                .queryParam(PARAM_CODE_CHALLENGE_METHOD, request.codeChallengeMethod())
                .queryParam(PARAM_RESOURCE, request.resource());
        if (StringUtils.isNotBlank(request.state())) {
            consentUri.queryParam(PARAM_STATE, request.state());
        }
        return consentUri.build();
    }
}
