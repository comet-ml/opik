package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_QUERY_PARAM;

@RequiredArgsConstructor
@Slf4j
class RemoteAuthService implements AuthService {
    private static final String USER_NOT_FOUND = "User not found";
    private static final String NOT_LOGGED_USER = "Please login first";

    private static final Map<String, Set<String>> PUBLIC_ENDPOINTS = new HashMap<>() {
        {
            // Private projects related endpoints
            put("^/v1/private/projects/?$", Set.of("GET"));
            put("^/v1/private/projects/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/projects/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/metrics/?$",
                    Set.of("POST"));
            put("^/v1/private/spans/?$", Set.of("GET"));
            put("^/v1/private/spans/stats/?$", Set.of("GET"));
            put("^/v1/private/spans/feedback-scores/names/?$", Set.of("GET"));
            put("^/v1/private/spans/search/?$", Set.of("POST"));
            put("^/v1/private/traces/?$", Set.of("GET"));
            put("^/v1/private/traces/stats/?$", Set.of("GET"));
            put("^/v1/private/traces/feedback-scores/names/?$", Set.of("GET"));
            put("^/v1/private/traces/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/traces/threads/?$", Set.of("GET"));
            put("^/v1/private/traces/threads/retrieve/?$", Set.of("POST"));
            put("^/v1/private/traces/search/?$", Set.of("POST"));

            // Public datasets related endpoints
            put("^/v1/private/datasets/?$", Set.of("GET"));
            put("^/v1/private/datasets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/datasets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/items/?$",
                    Set.of("GET"));
            put("^/v1/private/datasets/items/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/datasets/retrieve/?$", Set.of("POST"));
            put("^/v1/private/datasets/items/stream/?$", Set.of("POST"));
        }
    };

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig.UrlConfig reactServiceUrl;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull CacheService cacheService;

    @Builder(toBuilder = true)
    record AuthRequest(String workspaceName, String path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Builder(toBuilder = true)
    record AuthResponse(String user, String workspaceId, String workspaceName, List<Quota> quotas) {
    }

    @Builder(toBuilder = true)
    record ValidatedAuthCredentials(
            boolean shouldCache, String userName, String workspaceId, String workspaceName, List<Quota> quotas) {
    }

    @Override
    public void authenticate(HttpHeaders headers, Cookie sessionToken, ContextInfoHolder contextInfo) {
        UriInfo uriInfo = contextInfo.uriInfo();
        String path = uriInfo.getRequestUri().getPath();
        var currentWorkspaceName = Optional.ofNullable(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .orElseGet(() -> uriInfo.getQueryParameters().getFirst(WORKSPACE_QUERY_PARAM));
        if (StringUtils.isBlank(currentWorkspaceName)) {
            log.warn("Workspace name is missing");
            throw new ClientErrorException(MISSING_WORKSPACE, Response.Status.FORBIDDEN);
        }

        try {
            if (sessionToken != null) {
                authenticateUsingSessionToken(sessionToken, currentWorkspaceName, path);
            } else {
                authenticateUsingApiKey(headers, currentWorkspaceName, path);
            }
        } catch (ClientErrorException authException) {
            if (!isDefaultWorkspace(currentWorkspaceName) && isNotAuthenticated(authException)
                    && isEndpointPublic(contextInfo)) {
                log.info("Using visibility PUBLIC for endpoint: {}", path);
                String workspaceId = getWorkspaceId(currentWorkspaceName);
                requestContext.get().setWorkspaceId(workspaceId);
                requestContext.get().setWorkspaceName(currentWorkspaceName);
                requestContext.get().setVisibility(Visibility.PUBLIC);
                requestContext.get().setUserName("Public");
                return;
            }
            throw authException;
        }
    }

    @Override
    public void authenticateSession(Cookie sessionToken) {
        if (sessionToken == null || StringUtils.isBlank(sessionToken.getValue())) {
            log.info("No cookies found");
            throw new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
    }

    private void authenticateUsingSessionToken(Cookie sessionToken, String workspaceName, String path) {
        if (isDefaultWorkspace(workspaceName)) {
            log.warn("Default workspace name is not allowed for UI authentication");
            throw new ClientErrorException(
                    NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        }
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("auth-session")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .cookie(sessionToken)
                .post(Entity.json(AuthRequest.builder().workspaceName(workspaceName).path(path).build()))) {
            var credentials = verifyResponse(response);
            setCredentialIntoContext(credentials.user(), credentials.workspaceId(),
                    Optional.ofNullable(credentials.workspaceName()).orElse(workspaceName), credentials.quotas());
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
            cacheService.cache(apiKey, workspaceName, credentials.userName(), credentials.workspaceId(),
                    credentials.workspaceName(), credentials.quotas);
        }
        setCredentialIntoContext(credentials.userName(), credentials.workspaceId(),
                Optional.ofNullable(credentials.workspaceName()).orElse(workspaceName), credentials.quotas);
        requestContext.get().setApiKey(apiKey);
    }

    private ValidatedAuthCredentials validateApiKeyAndGetCredentials(String workspaceName, String apiKey, String path) {
        var credentials = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName);
        if (credentials.isEmpty()) {
            log.debug("User and workspace id not found in cache for API key");
            try (var response = client.target(URI.create(reactServiceUrl.url()))
                    .path("opik")
                    .path("auth")
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
                        .workspaceName(authResponse.workspaceName())
                        .quotas(authResponse.quotas())
                        .build();
            }
        } else {
            return ValidatedAuthCredentials.builder()
                    .shouldCache(false)
                    .userName(credentials.get().userName())
                    .workspaceId(credentials.get().workspaceId())
                    .workspaceName(credentials.get().workspaceName())
                    .quotas(credentials.get().quotas())
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

    private void setCredentialIntoContext(
            String userName, String workspaceId, String workspaceName, List<Quota> quotas) {
        log.debug("setting credentials into context, userName: {}, workspaceId: {}, workspaceName: {}, quotas: {}",
                userName, workspaceId, workspaceName, quotas);
        requestContext.get().setUserName(userName);
        requestContext.get().setWorkspaceId(workspaceId);
        requestContext.get().setWorkspaceName(workspaceName);
        requestContext.get().setQuotas(quotas);
    }

    private boolean isEndpointPublic(ContextInfoHolder contextInfo) {
        for (String pattern : PUBLIC_ENDPOINTS.keySet()) {
            if (contextInfo.uriInfo().getRequestUri().getPath().matches(pattern)) {
                Set<String> allowedMethods = PUBLIC_ENDPOINTS.get(pattern);
                if (allowedMethods.contains(contextInfo.method())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNotAuthenticated(ClientErrorException authException) {
        int status = authException.getResponse().getStatus();
        return status == Response.Status.UNAUTHORIZED.getStatusCode()
                || status == Response.Status.FORBIDDEN.getStatusCode();
    }

    private boolean isDefaultWorkspace(String workspaceName) {
        return ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(workspaceName);
    }

    private String getWorkspaceId(String workspaceName) {
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("workspaces")
                .path("workspace-id")
                .queryParam("name", workspaceName)
                .request()
                .get()) {

            return getWorkspaceIdFromResponse(response);
        }
    }

    private String getWorkspaceIdFromResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            log.error("Not found workspace by name : {}", errorResponse.msg());
            throw new ClientErrorException(errorResponse.msg(), Response.Status.BAD_REQUEST);
        }

        log.warn("Unexpected error while getting workspace id: {}", response.getStatus());
        throw new InternalServerErrorException();
    }
}
