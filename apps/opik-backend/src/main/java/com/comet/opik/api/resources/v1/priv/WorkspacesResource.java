package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.domain.WorkspaceConfigurationService;
import com.comet.opik.domain.WorkspaceMetricsService;
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
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Workspaces", description = "Workspace related resources")
public class WorkspacesResource {

    private final @NonNull WorkspaceMetricsService workspaceMetricsService;
    private final @NonNull WorkspaceConfigurationService workspaceConfigurationService;
    private final @NonNull Provider<RequestContext> requestContext;

    @Deprecated
    @POST
    @Path("/metrics/summaries")
    @Operation(operationId = "metricsSummary", summary = "Get metrics summary", description = "Get metrics summary", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace Metrics", content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response metricsSummary(
            @RequestBody(content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryRequest.class))) @NotNull @Valid WorkspaceMetricsSummaryRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieve workspace metrics summary for projectIds '{}', on workspace_id '{}'", request.projectIds(),
                workspaceId);
        WorkspaceMetricsSummaryResponse response = workspaceMetricsService.getWorkspaceFeedbackScoresSummary(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved workspace metrics summary for projectIds '{}', on workspace_id '{}'", request.projectIds(),
                workspaceId);

        return Response.ok().entity(response).build();
    }

    @Deprecated
    @POST
    @Path("/metrics")
    @Operation(operationId = "getMetric", summary = "Get metric daily data", description = "Get metric daily data", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace metric data by days", content = @Content(schema = @Schema(implementation = WorkspaceMetricResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getMetric(
            @RequestBody(content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryRequest.class))) @NotNull @Valid WorkspaceMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieve workspace metric data by days for projectIds '{}', on workspace_id '{}'",
                request.projectIds(),
                workspaceId);
        WorkspaceMetricResponse response = workspaceMetricsService.getWorkspaceFeedbackScores(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved workspace metric data by days for projectIds '{}', on workspace_id '{}'",
                request.projectIds(),
                workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/costs/summaries")
    @Operation(operationId = "costsSummary", summary = "Get costs summary", description = "Get costs summary", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace Metrics", content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryResponse.Result.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response costsSummary(
            @RequestBody(content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryRequest.class))) @NotNull @Valid WorkspaceMetricsSummaryRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieve workspace costs summary for projectIds '{}', on workspace_id '{}'", request.projectIds(),
                workspaceId);
        var response = workspaceMetricsService.getWorkspaceCostsSummary(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved workspace costs summary for projectIds '{}', on workspace_id '{}'", request.projectIds(),
                workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/costs")
    @Operation(operationId = "getCost", summary = "Get cost daily data", description = "Get cost daily data", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace cost data by days", content = @Content(schema = @Schema(implementation = WorkspaceMetricResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getCost(
            @RequestBody(content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryRequest.class))) @NotNull @Valid WorkspaceMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieve workspace cost data by days for projectIds '{}', on workspace_id '{}'",
                request.projectIds(),
                workspaceId);
        request = request.toBuilder().name("cost").build();
        WorkspaceMetricResponse response = workspaceMetricsService.getWorkspaceCosts(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved workspace cost data by days for projectIds '{}', on workspace_id '{}'",
                request.projectIds(),
                workspaceId);

        return Response.ok().entity(response).build();
    }

    @GET
    @Path("/configurations")
    @Operation(operationId = "getWorkspaceConfiguration", summary = "Get workspace configuration", description = "Get workspace configuration", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace Configuration", content = @Content(schema = @Schema(implementation = WorkspaceConfiguration.class))),
            @ApiResponse(responseCode = "404", description = "Configuration Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getWorkspaceConfiguration() {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting workspace configuration for workspace_id '{}'", workspaceId);

        var configuration = workspaceConfigurationService.getConfiguration()
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("No workspace configuration found for workspace '{}'", workspaceId);
                    return Mono.error(new NotFoundException("No workspace configuration found for workspace"));
                }))
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found workspace configuration for workspace_id '{}'", workspaceId);

        return Response.ok().entity(configuration).build();
    }

    @PUT
    @Path("/configurations")
    @Operation(operationId = "upsertWorkspaceConfiguration", summary = "Upsert workspace configuration", description = "Upsert workspace configuration", responses = {
            @ApiResponse(responseCode = "200", description = "Configuration Updated", content = @Content(schema = @Schema(implementation = WorkspaceConfiguration.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response upsertWorkspaceConfiguration(
            @RequestBody(content = @Content(schema = @Schema(implementation = WorkspaceConfiguration.class))) @Valid @NotNull WorkspaceConfiguration configuration) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Upserting workspace configuration for workspace_id '{}'", workspaceId);

        workspaceConfigurationService.upsertConfiguration(configuration)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Upserted workspace configuration for workspace_id '{}'", workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/configurations")
    @Operation(operationId = "deleteWorkspaceConfiguration", summary = "Delete workspace configuration", description = "Delete workspace configuration", responses = {
            @ApiResponse(responseCode = "204", description = "Configuration Deleted"),
            @ApiResponse(responseCode = "404", description = "Configuration Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteWorkspaceConfiguration() {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting workspace configuration for workspace_id '{}'", workspaceId);

        workspaceConfigurationService.deleteConfiguration()
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted workspace configuration for workspace_id '{}'", workspaceId);

        return Response.noContent().build();
    }
}
