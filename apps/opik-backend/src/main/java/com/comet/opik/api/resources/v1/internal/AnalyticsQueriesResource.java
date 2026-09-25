package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnalyticsQueryRequest;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.ScopedAnalyticsQueryRequest;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.FreeFormSqlAccount;
import com.comet.opik.domain.FreeFormSqlQueryService;
import com.comet.opik.infrastructure.CustomChartsConfig;
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
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Internal, authenticated endpoints that run caller-supplied read-only SQL against ClickHouse, always bounded to the
 * caller's workspace. Authentication is required only to derive that bound. Every query must return exactly one
 * column named {@code result}, produced via {@code toJSONString(...)}.
 *
 * <p>Two endpoints, running as <em>different</em> ClickHouse accounts whose row policies differ — not merely
 * whose grants do:
 *
 * <ul>
 * <li>{@code POST /} — scope in the body. Eight tables; those carrying {@code project_id} in their primary key are
 * restricted to {@code project_id} when supplied and cover the workspace when it is not, while {@code experiments},
 * {@code experiment_items} and {@code dataset_items} always cover the workspace. Gated on {@code ollieEnabled} and
 * {@code customCharts.enabledWorkspaces}.</li>
 * <li>{@code POST /projects/{projectId}} — scope in the path, and the older of the two. Three tables, every one
 * bound to workspace <em>and</em> project; its request body has no project field at all. Gated on
 * {@code ollieEnabled}. It is expected to be removed once its callers move to the endpoint above, which is why
 * that one carries no qualifier in its path.</li>
 * </ul>
 *
 * <p>Either gate returns {@code 501 Not Implemented} when closed, with no ClickHouse access.
 */
@Path("/v1/internal/analytics-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "System analytics queries", description = "Internal endpoints to run free-form analytics SQL")
public class AnalyticsQueriesResource {

    private final @NonNull FreeFormSqlQueryService freeFormSqlQueryService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles;
    private final @NonNull @Config("customCharts") CustomChartsConfig customCharts;

    @POST
    @Path("/projects/{projectId}")
    @Operation(operationId = "executeAnalyticsQuery", summary = "Execute Agent Insights free-form SQL", description = "Runs Ollie-generated read-only SQL bounded to the caller's workspace and the requested project. Returns 501 when the Agent Insights toggle is off.", responses = {
            @ApiResponse(responseCode = "200", description = "Query results", content = @Content(schema = @Schema(implementation = AnalyticsQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Agent Insights queries are not enabled")})
    @RateLimited
    public Response executeQuery(@PathParam("projectId") @NotNull UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnalyticsQueryRequest.class))) @NotNull @Valid AnalyticsQueryRequest request) {

        if (!serviceToggles.isOllieEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }

        // The caller chooses the projection, so rewriting the result is not enforceable: a value returned
        // through base64() or substring() matches no rule written against the plain text.
        RedactionGuard.rejectUnmaskable(requestContext.get().isRedactResponse(), "Agent Insights free-form SQL");

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Executing Agent Insights free-form SQL for workspace '{}', project '{}'", workspaceId, projectId);

        return execute(FreeFormSqlAccount.STANDARD, workspaceId, projectId, request.query());
    }

    @POST
    @Operation(operationId = "executeScopedAnalyticsQuery", summary = "Execute free-form analytics SQL", description = "Runs read-only SQL bounded to the caller's workspace. Supply project_id to restrict traces, spans, feedback scores and trace threads to one project, or omit it to cover the whole workspace. Experiments, experiment items and dataset items always cover the whole workspace.", responses = {
            @ApiResponse(responseCode = "200", description = "Query results", content = @Content(schema = @Schema(implementation = AnalyticsQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Agent Insights is disabled, or Custom Charts is not enabled for this workspace")})
    @RateLimited
    public Response executeScopedQuery(
            @RequestBody(content = @Content(schema = @Schema(implementation = ScopedAnalyticsQueryRequest.class))) @NotNull @Valid ScopedAnalyticsQueryRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        if (!serviceToggles.isOllieEnabled()
                || !customCharts.getEnabledWorkspaces().contains(workspaceId)) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }

        RedactionGuard.rejectUnmaskable(requestContext.get().isRedactResponse(), "Custom Charts free-form SQL");

        log.info("Executing Custom Charts SQL for workspace '{}', project '{}'", workspaceId,
                request.projectId() == null ? "all" : request.projectId());

        return execute(FreeFormSqlAccount.EXTENDED, workspaceId, request.projectId(), request.query());
    }

    /**
     * The service stays async (ClickHouse v2 client); terminate here, the last responsible moment, since Dropwizard
     * is not reactive. join() wraps any failure in CompletionException — unwrap so the mapped WebApplicationException
     * (and its HTTP status) reaches the JAX-RS exception handling unchanged.
     */
    private Response execute(FreeFormSqlAccount account, String workspaceId, @Nullable UUID projectId,
            String query) {
        try {
            AnalyticsQueryResponse response = freeFormSqlQueryService
                    .executeQuery(account, workspaceId, projectId, query)
                    .join();
            return Response.ok(response).build();
        } catch (CompletionException e) {
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
    }
}
