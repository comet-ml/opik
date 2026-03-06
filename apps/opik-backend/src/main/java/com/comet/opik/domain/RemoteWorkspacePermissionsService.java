package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.infrastructure.AuthenticationConfig;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

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

        log.error("Unexpected error while fetching workspace permissions, status: {}", response.getStatus());
        throw new InternalServerErrorException();
    }
}
