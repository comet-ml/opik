package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.WorkspacePermissionsService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/workspace-permissions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Workspace permissions", description = "Workspace permissions related resources")
public class WorkspacePermissionsResource {

    private final @NonNull WorkspacePermissionsService workspacePermissionsService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getWorkspaceUserPermissions", summary = "Get workspace permissions for the authenticated user", description = "Get workspace permissions for the authenticated user", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace Permissions", content = @Content(schema = @Schema(implementation = WorkspaceUserPermissions.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getWorkspaceUserPermissions() {
        String workspaceName = requestContext.get().getWorkspaceName();
        String apiKey = requestContext.get().getApiKey();

        log.info("Getting workspace permissions for workspace '{}'", workspaceName);
        WorkspaceUserPermissions permissions = workspacePermissionsService.getPermissions(apiKey, workspaceName);
        log.info("Retrieved workspace permissions for workspace '{}'", workspaceName);

        return Response.ok(permissions).build();
    }
}