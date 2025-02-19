package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.WorkspaceMetadata;
import com.comet.opik.domain.workspaces.WorkspaceMetadataService;
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

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Workspaces", description = "Workspace related resources")
public class WorkspaceResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final WorkspaceMetadataService service;

    @GET
    @Path("metadata")
    @Operation(operationId = "getWorkspaceMetadata", summary = "Get workspace metadata", tags = {
            "Workspaces"}, responses = {
                    @ApiResponse(responseCode = "200", description = "Workspace metadata", content = @Content(schema = @Schema(implementation = WorkspaceMetadata.class))),
                    @ApiResponse(responseCode = "404", description = "Workspace not found"),
            })
    public Response getWorkspaceMetadata() {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting metadata by on workspace_id '{}'", workspaceId);

        var workspaceMetadata = service.getWorkspaceMetadata(workspaceId)
                .map(metadata -> new WorkspaceMetadata(metadata.canUseDynamicSorting()))
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got metadata '{}' for workspace_id '{}' ", workspaceMetadata, workspaceId);

        return Response.ok(workspaceMetadata).build();
    }
}
