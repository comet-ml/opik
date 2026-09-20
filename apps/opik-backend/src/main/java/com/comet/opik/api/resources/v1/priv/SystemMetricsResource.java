package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.systemmetrics.SystemMetricBatch;
import com.comet.opik.systemmetrics.SystemMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Path("/v1/private/projects/{projectId}/system-metrics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "System metrics", description = "Application resource metrics stored in ClickHouse")
public class SystemMetricsResource {

    private final @NonNull SystemMetricsService systemMetricsService;
    private final @NonNull ProjectService projectService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/batch")
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    @Operation(operationId = "ingestProjectSystemMetrics", summary = "Ingest an Agent system metrics batch")
    public Response ingestSystemMetrics(
            @PathParam("projectId") UUID projectId,
            @NotNull @Valid SystemMetricBatch batch) {
        var workspaceId = requestContext.get().getWorkspaceId();
        projectService.validateProjectIdExists(projectId, workspaceId);
        var accepted = systemMetricsService.ingest(workspaceId, projectId, batch);
        return Response.accepted(new IngestionResponse(accepted)).build();
    }

    @GET
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    @Operation(operationId = "getProjectSystemMetrics", summary = "Query an Agent instance system metric")
    public Response getSystemMetrics(
            @PathParam("projectId") UUID projectId,
            @QueryParam("instance_id") @NotBlank String instanceId,
            @QueryParam("metric_name") @NotBlank String metricName,
            @QueryParam("from") Instant from,
            @QueryParam("to") Instant to) {
        var workspaceId = requestContext.get().getWorkspaceId();
        projectService.validateProjectIdExists(projectId, workspaceId);
        var response = systemMetricsService.query(
                workspaceId,
                projectId,
                instanceId,
                metricName,
                from,
                to);
        return Response.ok(response).build();
    }

    @GET
    @Path("/instances")
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    @Operation(operationId = "getProjectSystemMetricInstances", summary = "List active Agent metric instances")
    public Response getSystemMetricInstances(@PathParam("projectId") UUID projectId) {
        var workspaceId = requestContext.get().getWorkspaceId();
        projectService.validateProjectIdExists(projectId, workspaceId);
        return Response.ok(systemMetricsService.listInstances(workspaceId, projectId)).build();
    }

    public record IngestionResponse(long accepted) {
    }
}
