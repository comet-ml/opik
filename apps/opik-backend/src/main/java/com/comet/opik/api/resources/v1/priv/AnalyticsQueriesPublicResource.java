package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.ScopedAnalyticsQueryRequest;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.FreeFormSqlAccount;
import com.comet.opik.domain.FreeFormSqlConcurrencyLimiter;
import com.comet.opik.domain.FreeFormSqlQueryService;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.infrastructure.redaction.RedactionGuard;
import com.google.common.base.Throwables;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.concurrent.CompletionException;

/**
 * Public entry point for Custom Charts free-form SQL, called by the Opik MCP server and by API keys directly. Runs on
 * the EXTENDED account with the same checks as the internal scoped endpoint. Its per-workspace budget is its own, so
 * programmatic callers cannot starve the dashboard and Insights Views widget queries.
 */
@Path("/v1/private/analytics-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Tag(name = "Analytics queries", description = "Free-form analytics SQL for Custom Charts")
public class AnalyticsQueriesPublicResource {

    private final FreeFormSqlQueryService freeFormSqlQueryService;
    private final FreeFormSqlConcurrencyLimiter concurrencyLimiter;
    private final Provider<RequestContext> requestContext;
    private final ServiceTogglesConfig serviceToggles;
    private final CustomChartsConfig customCharts;

    // Hand-written rather than Lombok's: image builds do not read lombok.config, so a generated constructor loses the
    // @Config qualifiers and customCharts is injected as an empty default instance (OPIK-8548).
    @Inject
    public AnalyticsQueriesPublicResource(
            @NonNull FreeFormSqlQueryService freeFormSqlQueryService,
            @NonNull FreeFormSqlConcurrencyLimiter concurrencyLimiter,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull @Config("customCharts") CustomChartsConfig customCharts) {
        this.freeFormSqlQueryService = freeFormSqlQueryService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.requestContext = requestContext;
        this.serviceToggles = serviceToggles;
        this.customCharts = customCharts;
    }

    @POST
    @Operation(operationId = "runAnalyticsQuery", summary = "Run free-form analytics SQL", description = "Runs read-only SQL bounded to the caller's workspace. Supply project_id to restrict traces, spans, feedback scores and trace threads to one project, or omit it to cover the whole workspace. Experiments, experiment items and dataset items always cover the whole workspace. Returns 501 when Custom Charts or its public endpoints are not enabled.", responses = {
            @ApiResponse(responseCode = "200", description = "Query results", content = @Content(schema = @Schema(implementation = AnalyticsQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "Too many queries, or too many running at once in this workspace; retry after the Retry-After delay", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Custom Charts or its public endpoints are not enabled")})
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    @RateLimited(value = "analyticsQueries:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response runQuery(
            @RequestBody(content = @Content(schema = @Schema(implementation = ScopedAnalyticsQueryRequest.class))) @NotNull @Valid ScopedAnalyticsQueryRequest request) {

        if (!customCharts.isPublicEndpointsEnabled(serviceToggles)) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }

        RedactionGuard.rejectUnmaskable(requestContext.get().isRedactResponse(), "Custom Charts free-form SQL");

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Running public Custom Charts SQL for workspace '{}', project '{}'", workspaceId,
                request.projectId() == null ? "all" : request.projectId());

        try {
            return Response.ok(concurrencyLimiter
                    .run(workspaceId, () -> freeFormSqlQueryService.executeQuery(FreeFormSqlAccount.EXTENDED,
                            workspaceId, request.projectId(), request.query()))
                    .join())
                    .build();
        } catch (CompletionException e) {
            // Unwrap so the mapped WebApplicationException, and its HTTP status, reaches the JAX-RS handling.
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
    }
}
