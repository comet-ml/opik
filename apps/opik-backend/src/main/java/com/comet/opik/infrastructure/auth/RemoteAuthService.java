package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspacePermissionsService;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.GenericType;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_QUERY_PARAM;

@RequiredArgsConstructor
@Slf4j
class RemoteAuthService implements AuthService {
    private static final String USER_NOT_FOUND = "User not found";
    private static final String NOT_LOGGED_USER = "Please login first";

    // Remote error bodies are arbitrary upstream content, so cap what reaches the logs.
    private static final int MAX_LOGGED_BODY_LENGTH = 512;

    // GenericType instances are thread-safe and expensive to build, so reuse a single instance.
    private static final GenericType<List<WorkspaceIdNameResponse>> WORKSPACE_LIST_TYPE = new GenericType<>() {
    };

    private static final Map<String, Set<String>> PUBLIC_ENDPOINTS = new HashMap<>() {
        {
            // Private projects related endpoints
            put("^/v1/private/projects/?$", Set.of("GET"));
            put("^/v1/private/projects/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/projects/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/metrics/?$",
                    Set.of("POST"));
            put("^/v1/private/projects/retrieve/?$", Set.of("POST"));
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

    private final @NonNull WorkspacePermissionsService workspacePermissionsService;

    /**
     * Whether the caller's workspace permissions are resolved at all.
     * <p>
     * Only read-time redaction needs them, and resolving them costs a call on the path every Opik request
     * takes. So it happens only where something acts on the answer; with the feature off no permission lookup
     * is made and the authentication call is exactly what it was before.
     */
    private final boolean resolvePermissions;

    @Builder(toBuilder = true)
    record AuthRequest(String workspaceName, String path,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> requiredPermissions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Builder(toBuilder = true)
    record AuthResponse(String user, String workspaceId, String workspaceName, List<Quota> quotas) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkspaceIdNameResponse(String workspaceId, String workspaceName) {
    }

    @Builder(toBuilder = true)
    record ValidatedAuthCredentials(
            boolean shouldCache,
            String userName,
            String workspaceId,
            String workspaceName,
            List<Quota> quotas,
            List<String> permissions) {

        static ValidatedAuthCredentials from(AuthResponse authResponse, List<String> permissions) {
            return ValidatedAuthCredentials.builder()
                    .shouldCache(true)
                    .userName(authResponse.user())
                    .workspaceId(authResponse.workspaceId())
                    .workspaceName(authResponse.workspaceName())
                    .quotas(authResponse.quotas())
                    .permissions(permissions)
                    .build();
        }

        static ValidatedAuthCredentials from(CacheService.AuthCredentials authCredentials) {
            return ValidatedAuthCredentials.builder()
                    .shouldCache(false)
                    .userName(authCredentials.userName())
                    .workspaceId(authCredentials.workspaceId())
                    .workspaceName(authCredentials.workspaceName())
                    .quotas(authCredentials.quotas())
                    .permissions(authCredentials.permissions())
                    .build();
        }

        CacheService.AuthCredentials toAuthCredentials() {
            return CacheService.AuthCredentials.builder()
                    .userName(userName)
                    .workspaceId(workspaceId)
                    .workspaceName(workspaceName)
                    .quotas(quotas)
                    .permissions(permissions)
                    .build();
        }
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

        List<String> requiredPermissions = contextInfo.requiredPermissions();

        try {
            if (sessionToken != null) {
                authenticateUsingSessionToken(sessionToken, currentWorkspaceName, path, requiredPermissions);
            } else {
                authenticateUsingApiKey(headers, currentWorkspaceName, path, requiredPermissions);
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

    @Override
    public List<WorkspaceInfo> listEligibleWorkspaces(Cookie sessionToken) {
        requireSession(sessionToken);
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("workspaces")
                .queryParam("withoutExtendedData", true)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // avoids gzip double-decompression issue in case of huge workspaces list
                .acceptEncoding("identity")
                .cookie(sessionToken)
                .get()) {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw toSessionAuthException(response);
            }
            return response.readEntity(WORKSPACE_LIST_TYPE).stream()
                    .filter(workspace -> !isDefaultWorkspace(workspace.workspaceName()))
                    .map(workspace -> WorkspaceInfo.builder()
                            .id(workspace.workspaceId())
                            .name(workspace.workspaceName())
                            .build())
                    .toList();
        }
    }

    @Override
    public void authorizeOAuth(@NonNull ValidatedToken token, @NonNull ContextInfoHolder contextInfo) {
        String path = contextInfo.uriInfo().getRequestUri().getPath();
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("auth-by-username")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // avoids gzip double-decompression issue, same as in listEligibleWorkspaces
                .acceptEncoding("identity")
                .header(OAUTH_USERNAME_HEADER, token.userName())
                .post(Entity.json(AuthRequest.builder()
                        .workspaceName(token.workspaceName())
                        .path(path)
                        .requiredPermissions(contextInfo.requiredPermissions())
                        .build()))) {
            var authResponse = verifyResponse(response);
            var credentials = ValidatedAuthCredentials.from(authResponse, grantedPermissions(
                    () -> workspacePermissionsService.getPermissionsByUsername(token.userName(),
                            token.workspaceName())));
            setCredentialIntoContext(credentials, token.workspaceName(), null);
        }
    }

    @Override
    public UserWorkspace authorizeWorkspace(Cookie sessionToken, @NonNull String workspaceName) {
        requireSession(sessionToken);
        if (isDefaultWorkspace(workspaceName)) {
            throw new ClientErrorException(NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        }
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("auth-session")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // avoids gzip double-decompression issue, same as in listEligibleWorkspaces
                .acceptEncoding("identity")
                .cookie(sessionToken)
                .post(Entity.json(AuthRequest.builder().workspaceName(workspaceName).build()))) {
            var authResponse = verifyResponse(response);
            return UserWorkspace.builder()
                    .userName(authResponse.user())
                    .workspaceId(authResponse.workspaceId())
                    .workspaceName(authResponse.workspaceName())
                    .build();
        }
    }

    private void requireSession(Cookie sessionToken) {
        if (sessionToken == null || StringUtils.isBlank(sessionToken.getValue())) {
            throw new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
    }

    private ClientErrorException toSessionAuthException(Response response) {
        if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()
                || response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            return new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
        throw unexpectedRemoteError("listing workspaces", response);
    }

    /**
     * Logs the remote response (status and best-effort body, for production debugging) and builds an
     * {@link InternalServerErrorException} carrying a custom message that identifies the failing operation. The body is
     * only logged, never surfaced to the caller, so no internal/remote detail bubbles up to the endpoint.
     */
    private InternalServerErrorException unexpectedRemoteError(String operation, Response response) {
        log.error("Unexpected error while {}, received status code: {}, body: '{}'",
                operation, response.getStatus(), readBodySafely(response));
        return new InternalServerErrorException("Unexpected error while " + operation);
    }

    /**
     * Reads the response body for diagnostics only, never to be surfaced to the caller. The result is capped at
     * {@link #MAX_LOGGED_BODY_LENGTH} characters: a remote error body is arbitrary upstream content (a proxy error page
     * or a stack trace, not necessarily our own JSON), so it must not be able to flood the logs or carry an unbounded
     * amount of upstream detail into them.
     */
    private static String readBodySafely(Response response) {
        try {
            if (!isEntityReadable(response)) {
                return "";
            }
            return StringUtils.abbreviate(response.readEntity(String.class), MAX_LOGGED_BODY_LENGTH);
        } catch (RuntimeException e) {
            log.warn("Failed to read remote response body for debugging", e);
            return "";
        }
    }

    /**
     * Guards a {@code readEntity} call: reports whether the response carries an entity and that entity was buffered, so
     * that it can be read, and read again. A client response entity is backed by a single-shot input stream, so without
     * buffering the first reader consumes it and every later read fails. Buffering is idempotent — an already buffered
     * entity reports success — so callers do not need to track whether it already happened.
     *
     * @return {@code false} when there is no entity, or when it could not be buffered and is therefore unsafe to read
     */
    private static boolean isEntityReadable(Response response) {
        if (!response.hasEntity()) {
            return false;
        }
        try {
            return response.bufferEntity();
        } catch (RuntimeException e) {
            log.warn("Failed to buffer remote response entity, status: '{}'", response.getStatus(), e);
            return false;
        }
    }

    /**
     * Reports whether the body should be read as JSON, matching what the registered Jackson provider actually parses
     * rather than only the exact {@code application/json} type. A structured suffix ({@code application/problem+json})
     * and a non-{@code application} type ({@code text/json}) both deserialize fine, so gating on
     * {@code APPLICATION_JSON_TYPE.isCompatible} would discard a perfectly good remote message. A wildcard or absent
     * type tells us nothing about the body and is not treated as JSON — including a wildcard carrying the suffix
     * ({@code application/*+json}), which is not a legal response content type in the first place.
     */
    private static boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        var subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if (subtype.contains(MediaType.MEDIA_TYPE_WILDCARD)) {
            return false;
        }
        return "json".equals(subtype) || subtype.endsWith("+json");
    }

    /**
     * Extracts the error message from a non-successful react-service response without assuming the body is JSON.
     * <p>
     * The react service does not always answer with a {@link ReactServiceErrorResponse}: endpoints guarded by
     * Dropwizard's {@code @Auth} filter (for example {@code /opik/auth-session}) reject an expired or invalid session
     * cookie through the default {@code UnauthorizedHandler}, which replies {@code 401} with a {@code text/plain} body.
     * Reading such a response as {@link ReactServiceErrorResponse} makes Jersey raise
     * {@code MessageBodyProviderNotFoundException}, a {@code ProcessingException} that is not a
     * {@link ClientErrorException} and therefore escapes the auth filter and surfaces as a {@code 500} instead of the
     * intended client error.
     * <p>
     * Only a JSON body is treated as a message intended for the caller. Any other content type is a framework-level
     * response rather than an application error, so it is logged and the caller-facing {@code fallback} is used instead
     * of leaking the remote framework's wording. Every read is guarded by {@link #isEntityReadable(Response)}, which
     * also buffers, so that both the diagnostic logging here and any later reader of the same response can read it
     * rather than relying on being the first to consume the single-shot entity stream.
     *
     * @param fallback message used when the body is absent, not JSON, unreadable or empty
     */
    private static String readErrorMessage(Response response, String fallback) {
        if (!isEntityReadable(response)) {
            return fallback;
        }
        var mediaType = response.getMediaType();
        if (!isJson(mediaType)) {
            log.warn("React service replied with a non-JSON error body, status: '{}', contentType: '{}', body: '{}'",
                    response.getStatus(), mediaType, readBodySafely(response));
            return fallback;
        }
        try {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            return errorResponse == null || StringUtils.isBlank(errorResponse.msg())
                    ? fallback
                    : errorResponse.msg().strip();
        } catch (RuntimeException e) {
            log.warn("Failed to read react service error response, status: '{}', body: '{}'",
                    response.getStatus(), readBodySafely(response), e);
            return fallback;
        }
    }

    private void authenticateUsingSessionToken(Cookie sessionToken, String workspaceName, String path,
            List<String> requiredPermissions) {
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
                // avoids gzip double-decompression issue, same as in listEligibleWorkspaces
                .acceptEncoding("identity")
                .cookie(sessionToken)
                .post(Entity.json(AuthRequest.builder()
                        .workspaceName(workspaceName)
                        .path(path)
                        .requiredPermissions(requiredPermissions)
                        .build()))) {
            var authResponse = verifyResponse(response);
            var credentials = ValidatedAuthCredentials.from(authResponse, grantedPermissions(
                    () -> workspacePermissionsService.getPermissionsBySession(sessionToken.getValue(),
                            workspaceName)));
            setCredentialIntoContext(credentials, workspaceName, sessionToken.getValue());
        }
    }

    private void authenticateUsingApiKey(HttpHeaders headers, String workspaceName, String path,
            List<String> requiredPermissions) {
        var apiKey = Optional.ofNullable(headers.getHeaderString(HttpHeaders.AUTHORIZATION)).orElse("");
        if (apiKey.isBlank()) {
            log.info("API key not found in headers");
            throw new ClientErrorException(MISSING_API_KEY, Response.Status.UNAUTHORIZED);
        }
        var credentials = validateApiKeyAndGetCredentials(workspaceName, apiKey, path, requiredPermissions);
        if (credentials.shouldCache()) {
            log.debug("Caching user and workspace id for API key");
            cacheService.cache(apiKey, workspaceName, requiredPermissions, credentials.toAuthCredentials());
        }
        setCredentialIntoContext(credentials, workspaceName, apiKey);
    }

    private ValidatedAuthCredentials validateApiKeyAndGetCredentials(String workspaceName, String apiKey, String path,
            List<String> requiredPermissions) {
        var credentials = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName,
                requiredPermissions);
        if (credentials.isEmpty()) {
            log.debug("User and workspace id not found in cache for API key");
            try (var response = client.target(URI.create(reactServiceUrl.url()))
                    .path("opik")
                    .path("auth")
                    .request()
                    .accept(MediaType.APPLICATION_JSON)
                    // avoids gzip double-decompression issue, same as in listEligibleWorkspaces
                    .acceptEncoding("identity")
                    .header(HttpHeaders.AUTHORIZATION,
                            apiKey)
                    .post(Entity.json(AuthRequest.builder()
                            .workspaceName(workspaceName)
                            .path(path)
                            .requiredPermissions(requiredPermissions)
                            .build()))) {
                var authResponse = verifyResponse(response);
                return ValidatedAuthCredentials.from(authResponse, grantedPermissions(
                        () -> workspacePermissionsService.getPermissions(apiKey, workspaceName)));
            }
        } else {
            return ValidatedAuthCredentials.from(credentials.get());
        }
    }

    /**
     * The caller's granted permission names, or none.
     * <p>
     * Read from the permissions API rather than the authentication response, so the answer is data about what
     * the caller may see and not a by-product of whether it could be authenticated. Skipped entirely when
     * nothing acts on it.
     * <p>
     * A lookup that fails leaves the caller with no permissions, which redacts. That is the safe direction for
     * a feature whose purpose is withholding, but it does mean a permissions outage reads as masked content
     * rather than as an error, so it is logged at warn to stay diagnosable.
     */
    private List<String> grantedPermissions(Supplier<WorkspaceUserPermissions> lookup) {
        if (!resolvePermissions) {
            return List.of();
        }

        try {
            var permissions = lookup.get().permissions();
            return permissions == null
                    ? List.of()
                    : permissions.stream()
                            .filter(permission -> Boolean.parseBoolean(permission.permissionValue()))
                            .map(WorkspaceUserPermissions.Permission::permissionName)
                            .toList();
        } catch (RuntimeException lookupFailed) {
            log.warn("Could not resolve workspace permissions, treating the caller as unprivileged",
                    lookupFailed);
            return List.of();
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
            throw new ClientErrorException(readErrorMessage(response, NOT_LOGGED_USER),
                    Response.Status.UNAUTHORIZED);
        } else if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            // EM never returns FORBIDDEN as of now
            throw new ClientErrorException(
                    NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            throw new ClientErrorException(readErrorMessage(response, MISSING_WORKSPACE),
                    Response.Status.BAD_REQUEST);
        }
        throw unexpectedRemoteError("authenticating user", response);
    }

    private void setCredentialIntoContext(
            ValidatedAuthCredentials credentials, String fallbackWorkspaceName, String apiKey) {
        var workspaceName = Optional.ofNullable(credentials.workspaceName()).orElse(fallbackWorkspaceName);
        log.debug(
                "setting credentials into context, userName: '{}', workspaceId: '{}', workspaceName: '{}', quotas: '{}'",
                credentials.userName(), credentials.workspaceId(), workspaceName, credentials.quotas());
        requestContext.get().setUserName(credentials.userName());
        requestContext.get().setWorkspaceId(credentials.workspaceId());
        requestContext.get().setWorkspaceName(workspaceName);
        requestContext.get().setQuotas(credentials.quotas());
        requestContext.get().setApiKey(apiKey);
        requestContext.get().setPermissions(credentials.permissions() == null
                ? Set.of()
                : Set.copyOf(credentials.permissions()));
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
                // avoids gzip double-decompression issue, same as in listEligibleWorkspaces
                .acceptEncoding("identity")
                .get()) {

            return getWorkspaceIdFromResponse(response);
        }
    }

    private String getWorkspaceIdFromResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var message = readErrorMessage(response, MISSING_WORKSPACE);
            // An unknown workspace name is a caller mistake on the public-endpoint fallback path, not a server fault.
            log.warn("Workspace not found by name: '{}'", message);
            throw new ClientErrorException(message, Response.Status.BAD_REQUEST);
        }

        throw unexpectedRemoteError("getting workspace id", response);
    }
}
