package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemType;
import com.comet.opik.api.AnnotationQueueItemsAdd;
import com.comet.opik.api.AnnotationQueueItemsDelete;
import com.comet.opik.api.AnnotationQueueScope;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
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
import java.util.Map;
import java.util.UUID;

@Path("/v1/private/annotation-queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "AnnotationQueues", description = "Annotation Queue resources")
public class AnnotationQueuesResource {

    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findAnnotationQueues", summary = "Find annotation queues", description = "Find annotation queues with filtering and sorting", responses = {
            @ApiResponse(responseCode = "200", description = "Annotation queues page", content = @Content(schema = @Schema(implementation = AnnotationQueue.AnnotationQueuePage.class)))
    })
    @JsonView(AnnotationQueue.View.Public.class)
    public Response findAnnotationQueues(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("scope") AnnotationQueueScope scope,
            @QueryParam("sorting") String sorting) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Finding annotation queues with page '{}', size '{}', search '{}', scope '{}' on workspaceId '{}'",
                page, size, search, scope, workspaceId);

        List<SortingField> sortingFields = List.of(); // TODO: Parse sorting string

        var annotationQueuePage = annotationQueueService.find(page, size, search, scope, sortingFields)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Found '{}' annotation queues on workspaceId '{}'", annotationQueuePage.content().size(), workspaceId);

        return Response.ok().entity(annotationQueuePage).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getAnnotationQueueById", summary = "Get annotation queue by id", description = "Get annotation queue by id", responses = {
            @ApiResponse(responseCode = "200", description = "Annotation queue resource", content = @Content(schema = @Schema(implementation = AnnotationQueue.class)))
    })
    @JsonView(AnnotationQueue.View.Public.class)
    public Response getAnnotationQueueById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding annotation queue by id '{}' on workspaceId '{}'", id, workspaceId);

        String userName = requestContext.get().getUserName();

        var annotationQueue = annotationQueueService.findById(id)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Found annotation queue by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(annotationQueue).build();
    }

    @POST
    @Operation(operationId = "createAnnotationQueue", summary = "Create annotation queue", description = "Create annotation queue", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/annotation-queues/{queueId}", schema = @Schema(implementation = String.class))
            }),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response createAnnotationQueue(
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueue.class))) @JsonView(AnnotationQueue.View.Create.class) @Valid AnnotationQueue annotationQueue,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Creating annotation queue with name '{}' on workspaceId '{}'", annotationQueue.name(), workspaceId);

        var createdQueue = annotationQueueService.create(annotationQueue)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Created annotation queue with id '{}' on workspaceId '{}'", createdQueue.id(), workspaceId);

        var location = uriInfo.getAbsolutePathBuilder().path(createdQueue.id().toString()).build();

        return Response.status(Response.Status.CREATED)
                .location(location)
                .entity(createdQueue)
                .build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateAnnotationQueue", summary = "Update annotation queue", description = "Update annotation queue", responses = {
            @ApiResponse(responseCode = "200", description = "Updated annotation queue", content = @Content(schema = @Schema(implementation = AnnotationQueue.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response updateAnnotationQueue(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueUpdate.class))) @JsonView(AnnotationQueue.View.Update.class) @Valid AnnotationQueueUpdate annotationQueueUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating annotation queue with id '{}' on workspaceId '{}'", id, workspaceId);

        var updatedQueue = annotationQueueService.update(id, annotationQueueUpdate).block();

        log.info("Updated annotation queue with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(updatedQueue).build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteAnnotationQueues", summary = "Delete annotation queues", description = "Delete annotation queues batch", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response deleteAnnotationQueues(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Deleting annotation queues by ids, count '{}' on workspaceId '{}'", batchDelete.ids().size(),
                workspaceId);

        annotationQueueService.delete(batchDelete.ids())
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Deleted annotation queues by ids, count '{}' on workspaceId '{}'", batchDelete.ids().size(),
                workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/items/add")
    @Operation(operationId = "addItemsToAnnotationQueue", summary = "Add items to annotation queue", description = "Add traces or threads to annotation queue", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    @RateLimited
    public Response addItemsToAnnotationQueue(
            @PathParam("id") UUID queueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueItemsAdd.class))) @Valid AnnotationQueueItemsAdd request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Adding '{}' items to annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        // Get the queue to determine the item type
        var queue = annotationQueueService.findById(queueId)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var itemType = AnnotationQueueItemType.fromScope(queue.scope());

        annotationQueueService.addItems(queueId, request.ids(), itemType)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Added '{}' items to annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/items")
    @Operation(operationId = "getAnnotationQueueItems", summary = "Get annotation queue items", description = "Get traces or threads in annotation queue", responses = {
            @ApiResponse(responseCode = "200", description = "Retrieved annotation queue items")
    })
    public Response getAnnotationQueueItems(
            @PathParam("id") UUID queueId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Getting items for annotation queue with id '{}' on workspaceId '{}'", queueId, workspaceId);

        // Get real items from the database
        var items = annotationQueueService.getItems(queueId, page, size)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var totalCount = annotationQueueService.getItemsCount(queueId)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var response = Map.of(
                "content", items,
                "total", totalCount,
                "sortable_by", List.of());

        log.info("Retrieved '{}' items for annotation queue with id '{}' on workspaceId '{}'",
                items.size(), queueId, workspaceId);

        return Response.ok(response).build();
    }

    @POST
    @Path("/{id}/items/delete")
    @Operation(operationId = "removeItemsFromAnnotationQueue", summary = "Remove items from annotation queue", description = "Remove traces or threads from annotation queue", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response removeItemsFromAnnotationQueue(
            @PathParam("id") UUID queueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueItemsDelete.class))) @Valid AnnotationQueueItemsDelete request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Removing '{}' items from annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        annotationQueueService.removeItems(queueId, request.ids())
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Removed '{}' items from annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        return Response.noContent().build();
    }
}
