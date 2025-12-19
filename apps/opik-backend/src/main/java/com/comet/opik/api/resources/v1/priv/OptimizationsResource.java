package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStudioLog;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.OptimizationFilter;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.OptimizationSearchCriteria;
import com.comet.opik.domain.OptimizationService;
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
import jakarta.ws.rs.PUT;
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

@Path("/v1/private/optimizations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Optimizations", description = "Optimization resources")
public class OptimizationsResource {

    private final @NonNull OptimizationService optimizationService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull FiltersFactory filtersFactory;

    @PUT
    @Operation(operationId = "upsertOptimization", summary = "Upsert optimization", description = "Upsert optimization", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/optimizations/{id}", schema = @Schema(implementation = String.class))})})
    @RateLimited
    public Response upsert(
            @RequestBody(content = @Content(schema = @Schema(implementation = Optimization.class))) @JsonView(Optimization.View.Write.class) @NotNull @Valid Optimization optimization,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Upserting optimization with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                optimization.id(), optimization.name(), optimization.datasetName(), workspaceId);
        var id = optimizationService.upsert(optimization)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("Upserted optimization with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                id, optimization.name(), optimization.datasetName(), workspaceId);

        return Response.created(uri).build();
    }

    @GET
    @Operation(operationId = "findOptimizations", summary = "Find optimizations", description = "Find optimizations", responses = {
            @ApiResponse(responseCode = "200", description = "Optimizations resource", content = @Content(schema = @Schema(implementation = Optimization.OptimizationPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView(Optimization.View.Public.class)
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("dataset_id") UUID datasetId,
            @QueryParam("name") @Schema(description = "Filter optimizations by name (partial match, case insensitive)") String name,
            @QueryParam("dataset_deleted") Boolean datasetDeleted,
            @QueryParam("filters") String filters) {

        List<OptimizationFilter> parsedFilters = (List<OptimizationFilter>) filtersFactory.newFilters(filters,
                OptimizationFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = OptimizationSearchCriteria.builder()
                .datasetId(datasetId)
                .name(name)
                .datasetDeleted(datasetDeleted)
                .filters(parsedFilters)
                .entityType(EntityType.TRACE)
                .build();

        log.info("Finding optimizations by '{}', page '{}', size '{}'", searchCriteria, page, size);
        var optimizations = optimizationService.find(page, size, searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found optimizations by '{}', count '{}', page '{}', size '{}'",
                searchCriteria, optimizations.size(), page, size);
        return Response.ok().entity(optimizations).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getOptimizationById", summary = "Get optimization by id", description = "Get optimization by id", responses = {
            @ApiResponse(responseCode = "200", description = "Optimization resource", content = @Content(schema = @Schema(implementation = Optimization.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(Optimization.View.Public.class)
    public Response get(@PathParam("id") UUID id) {
        log.info("Getting optimization by id '{}'", id);
        var optimization = optimizationService.getById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Got optimization by id '{}', datasetId '{}'", optimization.id(), optimization.datasetId());
        return Response.ok().entity(optimization).build();
    }

    @POST
    @Operation(operationId = "createOptimization", summary = "Create optimization", description = "Create optimization", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/optimizations/{id}", schema = @Schema(implementation = String.class))})})
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Optimization.class))) @JsonView(Optimization.View.Write.class) @NotNull @Valid Optimization optimization,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating optimization with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                optimization.id(), optimization.name(), optimization.datasetName(), workspaceId);

        // For Studio optimizations, inject the API key from the request header
        var optimizationToCreate = optimization;
        if (optimization.studioConfig() != null) {
            var opikApiKey = requestContext.get().getHeaders().getFirst(RequestContext.OPIK_API_KEY);
            var enrichedConfig = optimization.studioConfig().toBuilder()
                    .opikApiKey(opikApiKey)
                    .build();
            optimizationToCreate = optimization.toBuilder()
                    .studioConfig(enrichedConfig)
                    .build();
        }

        var id = optimizationService.upsert(optimizationToCreate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("Created optimization with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                id, optimization.name(), optimization.datasetName(), workspaceId);

        return Response.created(uri).build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteOptimizationsById", summary = "Delete optimizations by id", description = "Delete optimizations by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")})
    public Response deleteOptimizationsById(
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteIdsHolder.class))) @NotNull @Valid DeleteIdsHolder request) {
        log.info("Deleting optimizations, count '{}'", request.ids().size());
        optimizationService.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted optimizations, count '{}'", request.ids().size());
        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateOptimizationsById", summary = "Update optimization by id", description = "Update optimization by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")})
    public Response updateOptimizationsById(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = OptimizationUpdate.class))) @NotNull OptimizationUpdate request) {
        log.info("Update optimization with id '{}', with request '{}'", id, request);

        optimizationService.update(id, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updates optimization with id '{}'", id);

        return Response.noContent().build();
    }

    // ==================== Studio Endpoints ====================

    @GET
    @Path("/studio/{id}/cancel")
    @Operation(operationId = "cancelStudioOptimizations", summary = "Cancel Studio optimizations", description = "Cancel Studio optimizations by id", responses = {
            @ApiResponse(responseCode = "501", description = "Not Implemented")})
    public Response cancelStudioOptimization(@PathParam("id") UUID id) {
        log.info("Cancel Studio optimization endpoint called for id '{}' - not yet implemented", id);
        return Response.status(Response.Status.NOT_IMPLEMENTED).build();
    }

    @GET
    @Path("/studio/{id}/logs")
    @Operation(operationId = "getStudioOptimizationLogs", summary = "Get Studio optimization logs", description = "Get presigned S3 URL for downloading optimization logs", responses = {
            @ApiResponse(responseCode = "200", description = "Logs response", content = @Content(schema = @Schema(implementation = OptimizationStudioLog.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response studioGetLogs(@PathParam("id") UUID id) {
        log.info("Getting logs for Studio optimization id: '{}'", id);

        var logs = optimizationService.generateStudioLogsResponse(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Generated logs URL for Studio optimization id: '{}'", id);
        return Response.ok(logs).build();
    }
}
