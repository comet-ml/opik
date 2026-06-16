package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJobRequest;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsJobService.EnableResult;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/agent-insights/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Insights Jobs", description = "Per-(workspace, project) Agent Insights report configuration")
public class AgentInsightsJobsResource {

    private final @NonNull AgentInsightsJobService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "enableAgentInsightsJob", summary = "Enable Agent Insights job", description = "Enables the Agent Insights report for a project (idempotent on workspace+project) and triggers an immediate first run. Returns 201 on first enable, 200 if it already existed.", responses = {
            @ApiResponse(responseCode = "201", description = "Job created", headers = @Header(name = "Location", description = "URI of the created job", schema = @Schema(type = "string")), content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "200", description = "Job already existed", content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response enable(@Valid @NotNull AgentInsightsJobRequest request,
            @Context UriInfo uriInfo) {
        EnableResult result = service.enable(request.projectId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        if (result.created()) {
            URI location = uriInfo.getAbsolutePathBuilder()
                    .queryParam("project_id", request.projectId())
                    .build();
            return Response.created(location).entity(result.job()).build();
        }
        return Response.ok(result.job()).build();
    }

    @GET
    @Operation(operationId = "getAgentInsightsJob", summary = "Get Agent Insights job", description = "Returns the Agent Insights job for the (workspace, project), or 404 if none exists.", responses = {
            @ApiResponse(responseCode = "200", description = "Job", content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response get(@QueryParam("project_id") @NotNull UUID projectId) {
        AgentInsightsJob job = service.getByProject(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(job).build();
    }

    @POST
    @Path("/disable")
    @Operation(operationId = "disableAgentInsightsJob", summary = "Disable Agent Insights job", description = "Disables the Agent Insights job for a project (flips status to disabled; never deletes). 404 if none exists.", responses = {
            @ApiResponse(responseCode = "204", description = "Job disabled"),
            @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response disable(@Valid @NotNull AgentInsightsJobRequest request) {
        service.disable(request.projectId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }
}
