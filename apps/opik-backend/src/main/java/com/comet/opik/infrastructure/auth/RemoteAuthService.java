package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Optional;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;

@RequiredArgsConstructor
@Slf4j
class RemoteAuthService implements AuthService {
    private static final String USER_NOT_FOUND = "User not found";
    private static final String NOT_LOGGED_USER = "Please login first";

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig.UrlConfig apiKeyAuthUrl;
    private final @NonNull AuthenticationConfig.UrlConfig uiAuthUrl;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull CacheService cacheService;

    @Builder(toBuilder = true)
    record AuthRequest(String workspaceName, String path) {
    }

    @Builder(toBuilder = true)
    record AuthResponse(String user, String workspaceId) {
    }

    @Builder(toBuilder = true)
    record ValidatedAuthCredentials(boolean shouldCache, String userName, String workspaceId) {
    }

    @Override
    public void authenticate(HttpHeaders headers, Cookie sessionToken, String path) {
        var currentWorkspaceName = Optional.ofNullable(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .orElse("");
        if (currentWorkspaceName.isBlank()) {
            log.warn("Workspace name is missing");
            throw new ClientErrorException(MISSING_WORKSPACE, Response.Status.FORBIDDEN);
        }
        if (sessionToken != null) {
            authenticateUsingSessionToken(sessionToken, currentWorkspaceName, path);
            return;
        }
        authenticateUsingApiKey(headers, currentWorkspaceName, path);
    }

    @Override
    public void authenticateSession(Cookie sessionToken) {
        if (sessionToken == null || StringUtils.isBlank(sessionToken.getValue())) {
            log.info("No cookies found");
            throw new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
    }

    private void authenticateUsingSessionToken(Cookie sessionToken, String workspaceName, String path) {
        if (ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(workspaceName)) {
            log.warn("Default workspace name is not allowed for UI authentication");
            throw new ClientErrorException(
                    NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        }
        try (var response = client.target(URI.create(uiAuthUrl.url()))
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .cookie(sessionToken)
                .post(Entity.json(AuthRequest.builder().workspaceName(workspaceName).path(path).build()))) {
            var credentials = verifyResponse(response);
            setCredentialIntoContext(credentials.user(), credentials.workspaceId());
            requestContext.get().setApiKey(sessionToken.getValue());
        }
    }

    private void authenticateUsingApiKey(HttpHeaders headers, String workspaceName, String path) {
        var apiKey = Optional.ofNullable(headers.getHeaderString(HttpHeaders.AUTHORIZATION)).orElse("");
        if (apiKey.isBlank()) {
            log.info("API key not found in headers");
            throw new ClientErrorException(MISSING_API_KEY, Response.Status.UNAUTHORIZED);
        }
        var credentials = validateApiKeyAndGetCredentials(workspaceName, apiKey, path);
        if (credentials.shouldCache()) {
            log.debug("Caching user and workspace id for API key");
            cacheService.cache(apiKey, workspaceName, credentials.userName(), credentials.workspaceId());
        }
        setCredentialIntoContext(credentials.userName(), credentials.workspaceId());
        requestContext.get().setApiKey(apiKey);
    }

    private ValidatedAuthCredentials validateApiKeyAndGetCredentials(String workspaceName, String apiKey, String path) {
        var credentials = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName);
        if (credentials.isEmpty()) {
            log.debug("User and workspace id not found in cache for API key");
            try (var response = client.target(URI.create(apiKeyAuthUrl.url()))
                    .request()
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION,
                            apiKey)
                    .post(Entity.json(AuthRequest.builder().workspaceName(workspaceName).path(path).build()))) {
                var authResponse = verifyResponse(response);
                return ValidatedAuthCredentials.builder()
                        .shouldCache(true)
                        .userName(authResponse.user())
                        .workspaceId(authResponse.workspaceId())
                        .build();
            }
        } else {
            return ValidatedAuthCredentials.builder()
                    .shouldCache(false)
                    .userName(credentials.get().userName())
                    .workspaceId(credentials.get().workspaceId())
                    .build();
        }
    }

    private AuthResponse verifyResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var authResponse = response.readEntity(AuthResponse.class);
            if (StringUtils.isEmpty(authResponse.user())) {
                log.warn("User not found");
                throw new ClientErrorException(USER_NOT_FOUND, Response.Status.UNAUTHORIZED);
            }
            return authResponse;
        } else if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            throw new ClientErrorException(errorResponse.msg(), Response.Status.UNAUTHORIZED);
        } else if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            // EM never returns FORBIDDEN as of now
            throw new ClientErrorException(
                    NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            throw new ClientErrorException(errorResponse.msg(), Response.Status.BAD_REQUEST);
        }
        log.error("Unexpected error while authenticating user, received status code: {}", response.getStatus());
        throw new InternalServerErrorException();
    }

    private void setCredentialIntoContext(String userName, String workspaceId) {
        requestContext.get().setUserName(userName);
        requestContext.get().setWorkspaceId(workspaceId);
    }
}
