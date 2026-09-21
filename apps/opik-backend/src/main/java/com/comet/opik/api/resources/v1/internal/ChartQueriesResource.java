package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.ChartQueryRequest;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AnalyticsConsumer;
import com.comet.opik.domain.FreeFormSqlQueryDAO;
import com.comet.opik.domain.FreeFormSqlQueryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * Internal, authenticated endpoint for Custom Charts SQL. Deliberately separate from
 * {@link AnalyticsQueriesResource}: it runs as a different ClickHouse account, reads a wider set of tables, and
 * takes the project as an optional part of the request rather than a mandatory path segment. Keeping the two apart
 * means Agent Insights is untouched by this feature — its account, its scope and its contract all stay as they are.
 *
 * <p>Gated on {@code serviceToggles.customChartsEnabledWorkspaces}: a caller outside the allowlist gets
 * {@code 501 Not Implemented} and no ClickHouse access.
 *
 * <p>Omitting {@code project_id} scopes traces and spans to the whole workspace. That is a scoping choice, not a
 * privilege one — {@code workspace_id} is enforced by a restrictive row policy on every table either way, and a
 * project id is only ever able to narrow the result.
 *
 * <p>As with Agent Insights, the query must return exactly one column named {@code result}, produced via
 * {@code toJSONString(...)}.
 */
@Path("/v1/internal/chart-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Custom chart queries", description = "Internal endpoint to run Custom Charts free-form SQL")
public class ChartQueriesResource {

    private final @NonNull FreeFormSqlQueryService freeFormSqlQueryService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles;

    @POST
    @Operation(operationId = "executeChartQuery", summary = "Execute Custom Charts free-form SQL", description = "Runs read-only SQL for a workspace allowlisted for Custom Charts. Omit project_id to query the whole workspace. Returns 501 when the workspace is not allowlisted.", responses = {
            @ApiResponse(responseCode = "200", description = "Query results", content = @Content(schema = @Schema(implementation = AnalyticsQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Custom Charts is not enabled for this workspace")})
    @RateLimited
    public Response executeQuery(
            @RequestBody(content = @Content(schema = @Schema(implementation = ChartQueryRequest.class))) @NotNull @Valid ChartQueryRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        if (!serviceToggles.getCustomChartsEnabledWorkspaces().contains(workspaceId)) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }

        // The caller chooses the projection, so rewriting the result is not enforceable: a value returned
        // through base64() or substring() matches no rule written against the plain text.
        RedactionGuard.rejectUnmaskable(requestContext.get().isRedactResponse(), "Custom Charts free-form SQL");

        String projectScope = Optional.ofNullable(request.projectId())
                .map(Object::toString)
                .orElse(FreeFormSqlQueryDAO.PROJECT_SCOPE_ALL);

        log.info("Executing Custom Charts SQL for workspace '{}', project scope '{}'", workspaceId, projectScope);

        // Same reasoning as AnalyticsQueriesResource: the service stays async, so terminate at the last responsible
        // moment and unwrap CompletionException so the mapped status reaches JAX-RS unchanged.
        try {
            AnalyticsQueryResponse response = freeFormSqlQueryService
                    .executeQuery(AnalyticsConsumer.CUSTOM_DASHBOARD_CHARTS, workspaceId, projectScope, request.query())
                    .join();
            return Response.ok(response).build();
        } catch (CompletionException e) {
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
    }
}
