package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendRecommendationsResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.domain.AiSpendService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/ai-spend")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "AI Spend", description = "Coding-agent spend analytics")
public class AiSpendResource {

    private final @NonNull AiSpendService aiSpendService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/summary")
    @Operation(operationId = "getSpendSummary", summary = "Get spend summary", description = "Get coding-agent spend KPI summary", responses = {
            @ApiResponse(responseCode = "200", description = "Spend summary", content = @Content(schema = @Schema(implementation = WorkspaceMetricsSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendSummary(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend summary on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getSummary(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend summary on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/composition")
    @Operation(operationId = "getSpendComposition", summary = "Get spend composition", description = "Get coding-agent token-flow composition (Sankey)", responses = {
            @ApiResponse(responseCode = "200", description = "Spend composition", content = @Content(schema = @Schema(implementation = SpendCompositionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendComposition(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend composition on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getComposition(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend composition on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/composition/{laneKey}/breakdown")
    @Operation(operationId = "getSpendLaneBreakdown", summary = "Get spend lane breakdown", description = "Get the per-item breakdown for a composition lane", responses = {
            @ApiResponse(responseCode = "200", description = "Lane breakdown", content = @Content(schema = @Schema(implementation = SpendBreakdownResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendLaneBreakdown(
            @PathParam("laneKey") String laneKey,
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend breakdown for lane '{}' on workspace_id '{}'", laneKey, workspaceId);
        var response = aiSpendService.getBreakdown(request, laneKey)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend breakdown for lane '{}' on workspace_id '{}'", laneKey, workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/users")
    @Operation(operationId = "getSpendUsers", summary = "Get spend user leaderboard", description = "Get coding-agent spend per user", responses = {
            @ApiResponse(responseCode = "200", description = "User leaderboard", content = @Content(schema = @Schema(implementation = SpendUserPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendUsers(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("25") int size,
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend users on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getUsers(request, page, size)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend users on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/recommendations")
    @Operation(operationId = "getSpendRecommendations", summary = "Get spend recommendations", description = "Get coding-agent cost-saving recommendations", responses = {
            @ApiResponse(responseCode = "200", description = "Recommendations", content = @Content(schema = @Schema(implementation = SpendRecommendationsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendRecommendations(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend recommendations on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getRecommendations(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend recommendations on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }
}
