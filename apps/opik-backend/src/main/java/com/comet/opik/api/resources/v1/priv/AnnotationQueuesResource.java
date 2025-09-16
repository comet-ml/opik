package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueItemIds;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
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
