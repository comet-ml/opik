package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;

@Slf4j
@RequiredArgsConstructor
public class RemoteWorkspacePermissionsService implements WorkspacePermissionsService {

    record WorkspacePermissionsRequest(String workspaceName) {
    }

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig.UrlConfig reactServiceUrl;

    @Override
    public WorkspaceUserPermissions getPermissions(@NonNull String apiKey, @NonNull String workspaceName) {
        log.info("Requesting workspace permissions for workspace '{}'", workspaceName);

        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("workspace-permissions")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .post(Entity.json(new WorkspacePermissionsRequest(workspaceName)))) {

            return parseResponse(response);
        }
    }

    @Override
    public WorkspaceUserPermissions getPermissionsBySession(@NonNull String sessionToken,
            @NonNull String workspaceName) {
        log.info("Requesting workspace permissions for workspace '{}' on the session path", workspaceName);

        try (var response = permissionsRequest("workspace-permissions-session")
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .post(Entity.json(new WorkspacePermissionsRequest(workspaceName)))) {

            return parseResponse(response);
        }
    }

    @Override
    public WorkspaceUserPermissions getPermissionsByUsername(@NonNull String userName,
            @NonNull String workspaceName) {
        log.info("Requesting workspace permissions for workspace '{}' on the OAuth path", workspaceName);

        try (var response = permissionsRequest("workspace-permissions-by-username")
                .header(OAUTH_USERNAME_HEADER, userName)
                .post(Entity.json(new WorkspacePermissionsRequest(workspaceName)))) {

            return parseResponse(response);
        }
    }

    private Invocation.Builder permissionsRequest(String path) {
        return client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path(path)
                .request()
                .accept(MediaType.APPLICATION_JSON);
    }

    private WorkspaceUserPermissions parseResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(WorkspaceUserPermissions.class);
        } else if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            throw new ClientErrorException(errorResponse.msg(), Response.Status.UNAUTHORIZED);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            throw new ClientErrorException(errorResponse.msg(), Response.Status.BAD_REQUEST);
        }

        if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            // A platform that predates the session and OAuth variants of this endpoint. Named separately so
            // the cause is legible: the caller is left unprivileged, which redacts, and reading "not found"
            // rather than a generic failure is what tells an operator to deploy the platform side.
            log.warn("The platform does not expose this workspace permissions endpoint");
            throw new NotFoundException();
        }

        log.error("Unexpected error while fetching workspace permissions, status: {}", response.getStatus());
        throw new InternalServerErrorException();
    }
}
