package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ProjectNameHolder;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/check")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Check", description = "Access check resources")
public class CheckAccessResource {

    private final @NonNull ProjectService projectService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/project")
    @Operation(operationId = "retrieveProject", summary = "Check user access to workspace and project", description = "Check user access to workspace and project", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response checkProjectAccess(
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectNameHolder.class))) @Valid ProjectNameHolder projectNameHolder) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        String projectName = projectNameHolder.project();

        log.info("Check access to project '{}', on workspace_id '{}' for user {}", projectName, workspaceId, userName);
        projectService.retrieveByName(projectName);
        log.info("User {} has access to project '{}' on workspace_id '{}'", userName, projectName, workspaceId);

        return Response.noContent().build();
    }
}
