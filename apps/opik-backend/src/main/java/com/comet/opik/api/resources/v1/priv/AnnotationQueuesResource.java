package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueItemIds;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.filter.AnnotationQueueFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.AnnotationQueueSortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.AnnotationQueueService;
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
import jakarta.ws.rs.PATCH;
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

@Path("/v1/private/annotation-queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Annotation Queues", description = "Private annotation queue operations")
public class AnnotationQueuesResource {

    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull AnnotationQueueSortingFactory sortingFactory;
    private final @NonNull FiltersFactory filtersFactory;

    @GET
    @Operation(operationId = "findAnnotationQueues", summary = "Find annotation queues", description = "Find annotation queues with filtering and sorting", responses = {
            @ApiResponse(responseCode = "200", description = "Annotation queues page", content = @Content(schema = @Schema(implementation = AnnotationQueue.AnnotationQueuePage.class)))
    })
    @JsonView(AnnotationQueue.View.Public.class)
    public Response findAnnotationQueues(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter annotation queues by name (partial match, case insensitive)") String name,
            @QueryParam("filters") String filters,
            @QueryParam("sorting") String sorting) {

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var annotationQueueFilters = filtersFactory.newFilters(filters, AnnotationQueueFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = AnnotationQueueSearchCriteria.builder()
                .name(name)
                .filters(annotationQueueFilters)
                .sortingFields(sortingFields)
                .build();

        log.info("Finding annotation queues by '{}', page '{}', size '{}'", searchCriteria, page, size);
        var annotationQueues = annotationQueueService.find(page, size, searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found annotation queues by '{}', count '{}', page '{}', size '{}'",
                searchCriteria, annotationQueues.size(), page, size);
        return Response.ok().entity(annotationQueues).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getAnnotationQueueById", summary = "Get annotation queue by id", description = "Get annotation queue by id", responses = {
            @ApiResponse(responseCode = "200", description = "Annotation queue resource", content = @Content(schema = @Schema(implementation = AnnotationQueue.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView(AnnotationQueue.View.Public.class)
    public Response getAnnotationQueueById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding annotation queue by id '{}' on workspaceId '{}'", id, workspaceId);

        var annotationQueue = annotationQueueService.findById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found annotation queue by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(annotationQueue).build();
    }

    @POST
    @Path("/batch")
    @Operation(operationId = "createAnnotationQueueBatch", summary = "Create annotation queue batch", description = "Create multiple annotation queues for human annotation workflows", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response createAnnotationQueueBatch(
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueBatch.class))) @JsonView(AnnotationQueue.View.Write.class) @NotNull @Valid AnnotationQueueBatch batch) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating annotation queue batch with '{}' items, workspaceId '{}'",
                batch.annotationQueues().size(), workspaceId);

        var items = annotationQueueService.createBatch(batch)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created annotation queue batch with '{}' items, workspaceId '{}'",
                items, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Operation(operationId = "createAnnotationQueue", summary = "Create annotation queue", description = "Create annotation queue for human annotation workflows", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{annotationId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response createAnnotationQueue(
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueue.class))) @JsonView(AnnotationQueue.View.Write.class) @NotNull @Valid AnnotationQueue request,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating annotation queue with id '{}' on workspaceId '{}'",
                request.id(), workspaceId);

        var id = annotationQueueService.create(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created annotation queue with id '{}' on workspaceId '{}'",
                id, workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateAnnotationQueue", summary = "Update annotation queue", description = "Update annotation queue by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response updateAnnotationQueue(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueUpdate.class))) @NotNull @Valid AnnotationQueueUpdate request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating annotation queue with id '{}' on workspaceId '{}'", id, workspaceId);

        annotationQueueService.update(id, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updated annotation queue with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteAnnotationQueueBatch", summary = "Delete annotation queue batch", description = "Delete multiple annotation queues by their IDs", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteAnnotationQueueBatch(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDelete batch) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting annotation queue batch with '{}' items, workspaceId '{}'",
                batch.ids().size(), workspaceId);

        var deletedCount = annotationQueueService.deleteBatch(batch.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted annotation queue batch with '{}' items deleted, workspaceId '{}'",
                deletedCount, workspaceId);

        return Response.noContent().build();
    }

    //    annotation queue items

    @POST
    @Path("/{id}/items/add")
    @Operation(operationId = "addItemsToAnnotationQueue", summary = "Add items to annotation queue", description = "Add traces or threads to annotation queue", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response addItemsToAnnotationQueue(
            @PathParam("id") UUID queueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueItemIds.class))) @Valid AnnotationQueueItemIds request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Adding '{}' items to annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        annotationQueueService.addItems(queueId, request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Added '{}' items to annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/items/delete")
    @Operation(operationId = "removeItemsFromAnnotationQueue", summary = "Remove items from annotation queue", description = "Remove items from annotation queue", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response removeItemsFromAnnotationQueue(
            @PathParam("id") UUID queueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueItemIds.class))) @Valid AnnotationQueueItemIds request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Removing '{}' items from annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        annotationQueueService.removeItems(queueId, request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Removed '{}' items from annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        return Response.noContent().build();
    }
}
