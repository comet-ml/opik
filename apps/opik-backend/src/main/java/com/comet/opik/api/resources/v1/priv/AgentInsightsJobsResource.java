package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJobRequest;
import com.comet.opik.api.AgentInsightsJobUpdate;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsJobService.CreateResult;
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
import jakarta.ws.rs.PATCH;
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
    @Operation(operationId = "createAgentInsightsJob", summary = "Create Agent Insights job", description = "Creates the Agent Insights job for a project (idempotent on workspace+project). Returns 201 on first create, 200 if it already existed. Does not trigger a run — use the trigger endpoint for that.", responses = {
            @ApiResponse(responseCode = "201", description = "Job created", headers = @Header(name = "Location", description = "URI of the created job", schema = @Schema(type = "string")), content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "200", description = "Job already existed", content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response create(@Valid @NotNull AgentInsightsJobRequest request,
            @Context UriInfo uriInfo) {
        CreateResult result = service.create(request.projectId())
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

    @PATCH
    @Operation(operationId = "updateAgentInsightsJob", summary = "Update Agent Insights job", description = "Partially updates the Agent Insights job for a project (e.g. status; never deletes). Returns the updated job, or 404 if none exists.", responses = {
            @ApiResponse(responseCode = "200", description = "Job updated", content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response update(@QueryParam("project_id") @NotNull UUID projectId,
            @Valid @NotNull AgentInsightsJobUpdate update) {
        AgentInsightsJob job = service.update(projectId, update.status())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(job).build();
    }

    @POST
    @Path("/trigger")
    @Operation(operationId = "triggerAgentInsightsJob", summary = "Trigger Agent Insights job", description = "Triggers an immediate report run for an existing job (over the last 24h). Fire-and-forget; returns 202. 404 if none exists.", responses = {
            @ApiResponse(responseCode = "202", description = "Run accepted"),
            @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response trigger(@Valid @NotNull AgentInsightsJobRequest request) {
        service.triggerNow(request.projectId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.accepted().build();
    }
}
