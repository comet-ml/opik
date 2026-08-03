package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnalyticsQueryRequest;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.FreeFormSqlQueryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
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
 * Internal, authenticated endpoint that runs Ollie-generated read-only SQL against ClickHouse, bounded to the
 * caller's workspace and the requested project. Authentication is required only to derive the bounding
 * {@code workspace_id} ({@code project_id} comes from the body). Gated behind the {@code ollieEnabled}
 * toggle: when off it returns {@code 501 Not Implemented} and performs no ClickHouse access.
 *
 * <p>The caller's final query must return exactly one column named {@code result}, produced via
 * {@code toJSONString(...)}.
 */
@Path("/v1/internal/analytics-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "System analytics queries", description = "Internal endpoint to run Agent Insights free-form SQL")
public class AnalyticsQueriesResource {

    private final @NonNull FreeFormSqlQueryService freeFormSqlQueryService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles;

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

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Executing Agent Insights free-form SQL for workspace '{}', project '{}'", workspaceId, projectId);

        // The service stays async (ClickHouse v2 client); terminate here, the last responsible moment, since Dropwizard
        // is not reactive. join() wraps any failure in CompletionException — unwrap so the mapped WebApplicationException
        // (and its HTTP status) reaches the JAX-RS exception handling unchanged.
        try {
            AnalyticsQueryResponse response = freeFormSqlQueryService
                    .executeQuery(workspaceId, projectId, request.query())
                    .join();
            return Response.ok(response).build();
        } catch (CompletionException e) {
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
    }
}
