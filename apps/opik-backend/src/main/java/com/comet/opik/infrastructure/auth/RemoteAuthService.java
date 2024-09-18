package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.Optional;

import static com.comet.opik.infrastructure.AuthenticationConfig.UrlConfig;
import static com.comet.opik.infrastructure.auth.AuthCredentialsCacheService.AuthCredentials;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@RequiredArgsConstructor
@Slf4j
class RemoteAuthService implements AuthService {

    public static final String NOT_ALLOWED_TO_ACCESS_WORKSPACE = "User not allowed to access workspace";
    private final @NonNull Client client;
    private final @NonNull UrlConfig apiKeyAuthUrl;
    private final @NonNull UrlConfig uiAuthUrl;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull CacheService cacheService;
    private final @NonNull LockService lockService;

    record AuthRequest(String workspaceName) {
    }

    record AuthResponse(String user, String workspaceId) {
    }

    record ValidatedAuthCredentials(boolean shouldCache, String userName, String workspaceId) {
    }

    @Override
    public void authenticate(HttpHeaders headers, Cookie sessionToken) {

        var currentWorkspaceName = getCurrentWorkspaceName(headers);

        if (currentWorkspaceName.isBlank()
                || ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(currentWorkspaceName)) {
            log.warn("Default workspace name is not allowed");
            throw new ClientErrorException(Response.Status.FORBIDDEN);
        }

        if (sessionToken != null) {
            authenticateUsingSessionToken(sessionToken, currentWorkspaceName);
            requestContext.get().setWorkspaceName(currentWorkspaceName);
            return;
        }

        authenticateUsingApiKey(headers, currentWorkspaceName);
        requestContext.get().setWorkspaceName(currentWorkspaceName);
    }

    private String getCurrentWorkspaceName(HttpHeaders headers) {
        return Optional.ofNullable(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .orElse("");
    }

    private void authenticateUsingSessionToken(Cookie sessionToken, String workspaceName) {
        try (var response = client.target(URI.create(uiAuthUrl.url()))
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .cookie(sessionToken)
                .post(Entity.json(new AuthRequest(workspaceName)))) {

            AuthResponse credentials = verifyResponse(response);

            setCredentialIntoContext(credentials.user(), credentials.workspaceId());
            requestContext.get().setApiKey(sessionToken.getValue());
        }
    }

    private void authenticateUsingApiKey(HttpHeaders headers, String workspaceName) {

        String apiKey = Optional.ofNullable(headers.getHeaderString(HttpHeaders.AUTHORIZATION))
                .orElse("");

        if (apiKey.isBlank()) {
            log.info("API key not found in headers");
            throw new ClientErrorException(NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.UNAUTHORIZED);
        }

        var lock = new Lock(apiKey, workspaceName);

        ValidatedAuthCredentials credentials = lockService.executeWithLock(
                lock,
                Mono.fromCallable(() -> validateApiKeyAndGetCredentials(workspaceName, apiKey))
                        .subscribeOn(Schedulers.boundedElastic()))
                .block();

        if (credentials.shouldCache()) {
            log.debug("Caching user and workspace id for API key");
            cacheService.cache(apiKey, workspaceName, credentials.userName(), credentials.workspaceId());
        }

        setCredentialIntoContext(credentials.userName(), credentials.workspaceId());
        requestContext.get().setApiKey(apiKey);
    }

    private ValidatedAuthCredentials validateApiKeyAndGetCredentials(String workspaceName, String apiKey) {
        Optional<AuthCredentials> credentials = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey,
                workspaceName);

        if (credentials.isEmpty()) {
            log.debug("User and workspace id not found in cache for API key");

            try (var response = client.target(URI.create(apiKeyAuthUrl.url()))
                    .request()
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION,
                            apiKey)
                    .post(Entity.json(new AuthRequest(workspaceName)))) {

                AuthResponse authResponse = verifyResponse(response);
                return new ValidatedAuthCredentials(true, authResponse.user(), authResponse.workspaceId());
            }
        } else {
            return new ValidatedAuthCredentials(false, credentials.get().userName(), credentials.get().workspaceId());
        }
    }

    private AuthResponse verifyResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var authResponse = response.readEntity(AuthResponse.class);

            if (StringUtils.isEmpty(authResponse.user())) {
                log.warn("User not found");
                throw new ClientErrorException(Response.Status.UNAUTHORIZED);
            }

            return authResponse;
        } else if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
            throw new ClientErrorException(NOT_ALLOWED_TO_ACCESS_WORKSPACE,
                    Response.Status.UNAUTHORIZED);
        } else if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            throw new ClientErrorException("User has bot permission to the workspace", Response.Status.FORBIDDEN);
        } else if (response.getStatusInfo().getFamily() == Response.Status.Family.SERVER_ERROR) {
            log.error("Error while authenticating user");
            throw new ClientErrorException(Response.Status.INTERNAL_SERVER_ERROR);
        }

        log.error("Unexpected error while authenticating user, status code: {}", response.getStatus());
        throw new ClientErrorException(Response.Status.INTERNAL_SERVER_ERROR);
    }

    private void setCredentialIntoContext(String userName, String workspaceId) {
        requestContext.get().setUserName(userName);
        requestContext.get().setWorkspaceId(workspaceId);
    }

}
