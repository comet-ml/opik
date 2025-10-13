package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.LogItem;
import com.comet.opik.api.OptimizationStudioRun;
import com.comet.opik.domain.OptimizationStudioService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/optimization-studio/runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Optimization Studio", description = "Optimization Studio resources")
public class OptimizationStudioResource {

    private final @NonNull OptimizationStudioService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "createOptimizationStudioRun", summary = "Create optimization studio run", description = "Create optimization studio run", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/optimization-studio/runs/{id}", schema = @Schema(implementation = String.class))})})
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = OptimizationStudioRun.class))) @JsonView(OptimizationStudioRun.View.Write.class) @NotNull @Valid OptimizationStudioRun run,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating optimization studio run '{}', datasetId '{}', workspaceId '{}'",
                run.name(), run.datasetId(), workspaceId);

        var id = service.create(run)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("Created optimization studio run with id '{}', name '{}', workspaceId '{}'",
                id, run.name(), workspaceId);

        return Response.created(uri).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getOptimizationStudioRun", summary = "Get optimization studio run by id", description = "Get optimization studio run by id", responses = {
            @ApiResponse(responseCode = "200", description = "Optimization Studio Run resource", content = @Content(schema = @Schema(implementation = OptimizationStudioRun.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(OptimizationStudioRun.View.Public.class)
    public Response get(@PathParam("id") UUID id) {
        log.info("Getting optimization studio run by id '{}'", id);

        var run = service.getById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got optimization studio run by id '{}', name '{}'", run.id(), run.name());
        return Response.ok().entity(run).build();
    }

    @POST
    @Path("/{id}/logs")
    @Operation(operationId = "createOptimizationStudioRunLogs", summary = "Create logs for optimization studio run", description = "Create logs for optimization studio run", responses = {
            @ApiResponse(responseCode = "201", description = "Created")})
    @RateLimited
    public Response createLogs(
            @PathParam("id") UUID runId,
            @RequestBody(content = @Content(schema = @Schema(implementation = LogItem.class))) @NotNull @Valid List<LogItem> logs) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating {} logs for optimization studio run id '{}', workspaceId '{}'",
                logs.size(), runId, workspaceId);

        service.createLogs(runId, logs)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created {} logs for optimization studio run id '{}'", logs.size(), runId);

        return Response.status(Response.Status.CREATED).build();
    }

    @GET
    @Path("/{id}/logs")
    @Operation(operationId = "getOptimizationStudioRunLogs", summary = "Get logs for optimization studio run", description = "Get logs for optimization studio run", responses = {
            @ApiResponse(responseCode = "200", description = "Optimization Studio Run logs resource", content = @Content(schema = @Schema(implementation = LogItem.LogPage.class)))})
    public Response getLogs(
            @PathParam("id") UUID runId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("100") int size) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Getting logs for optimization studio run id '{}', workspaceId '{}', page '{}', size '{}'",
                runId, workspaceId, page, size);

        var criteria = LogCriteria.builder()
                .entityId(runId)
                .page(page)
                .size(size)
                .build();

        LogItem.LogPage logs = service.getLogs(criteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found {} logs for optimization studio run id '{}', page '{}'",
                logs.size(), runId, page);

        return Response.ok(logs).build();
    }
}
