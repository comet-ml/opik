package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.domain.MetricsService;
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

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/workspace")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Workspace", description = "Workspace related resources")
public class WorkspaceResource {

    private final @NonNull MetricsService metricsService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/metrics/summary")
    @Operation(operationId = "metricsSummary", summary = "Get metrics summary", description = "Get metrics summary", responses = {
            @ApiResponse(responseCode = "200", description = "Workspace Metrics", content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response metricsSummary(
            @RequestBody(content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryRequest.class))) @Valid WorkspaceMetricsSummaryRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieve workspace metrics summary for projectIds '{}', on workspace_id '{}'", request.projectIds(),
                workspaceId);
        WorkspaceMetricsSummaryResponse response = metricsService.getWorkspaceFeedbackScoresSummary(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved workspace metrics summary for projectIds '{}', on workspace_id '{}'", request.projectIds(),
                workspaceId);

        return Response.ok().entity(response).build();
    }
}
