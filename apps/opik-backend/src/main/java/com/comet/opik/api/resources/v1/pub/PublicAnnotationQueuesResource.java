package com.comet.opik.api.resources.v1.pub;

import com.comet.opik.api.Annotation;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.QueueItem;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.AnnotationService;
import com.comet.opik.domain.QueueItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Path("/v1/public/annotation-queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Public Annotation Queues", description = "Public API for SME annotation access")
@Slf4j
public class PublicAnnotationQueuesResource {

    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull QueueItemService queueItemService;
    private final @NonNull AnnotationService annotationService;

    @GET
    @Path("/{queueId}")
    @Operation(
            operationId = "getPublicAnnotationQueue",
            summary = "Get annotation queue for SME access",
            description = "Retrieve annotation queue details for SME annotation interface"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Annotation queue retrieved successfully",
            content = @Content(schema = @Schema(implementation = AnnotationQueue.class))
    )
    @ApiResponse(responseCode = "404", description = "Queue not found")
    public Response getQueue(@PathParam("queueId") UUID queueId) {
        log.info("Getting public annotation queue: '{}'", queueId);
        
        var queue = annotationQueueService.getQueuePublic(queueId);
        return Response.ok(queue).build();
    }

    @GET
    @Path("/{queueId}/next-item")
    @Operation(
            operationId = "getNextItemForAnnotation",
            summary = "Get next item for SME annotation",
            description = "Retrieve the next available item for annotation by SME"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Next item retrieved successfully",
            content = @Content(schema = @Schema(implementation = QueueItem.class))
    )
    @ApiResponse(responseCode = "204", description = "No items available for annotation")
    @ApiResponse(responseCode = "404", description = "Queue not found")
    public Response getNextItem(
            @PathParam("queueId") UUID queueId,
            @QueryParam("smeId") String smeId) {
        log.info("Getting next item for SME '{}' in queue: '{}'", smeId, queueId);
        
        var nextItem = queueItemService.getNextItemForSme(queueId, smeId);
        if (nextItem.isEmpty()) {
            return Response.noContent().build();
        }
        
        return Response.ok(nextItem.get()).build();
    }

    @POST
    @Path("/{queueId}/items/{itemId}/annotations")
    @Operation(
            operationId = "submitAnnotation",
            summary = "Submit annotation for queue item",
            description = "Submit SME annotation for a specific queue item"
    )
    @ApiResponse(
            responseCode = "201",
            description = "Annotation submitted successfully",
            content = @Content(schema = @Schema(implementation = Annotation.class))
    )
    @ApiResponse(responseCode = "400", description = "Invalid annotation data")
    @ApiResponse(responseCode = "404", description = "Queue or item not found")
    public Response submitAnnotation(
            @PathParam("queueId") UUID queueId,
            @PathParam("itemId") UUID itemId,
            @QueryParam("smeId") String smeId,
            @Valid AnnotationCreate annotation) {
        log.info("Submitting annotation for item '{}' by SME '{}' in queue: '{}'", itemId, smeId, queueId);
        
        var result = annotationService.createAnnotation(itemId, smeId, annotation);
        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    @PUT
    @Path("/{queueId}/items/{itemId}/status")
    @Operation(
            operationId = "updateItemStatus",
            summary = "Update queue item status",
            description = "Update the status of a queue item (e.g., mark as completed or skipped)"
    )
    @ApiResponse(responseCode = "200", description = "Item status updated successfully")
    @ApiResponse(responseCode = "404", description = "Queue or item not found")
    public Response updateItemStatus(
            @PathParam("queueId") UUID queueId,
            @PathParam("itemId") UUID itemId,
            @QueryParam("smeId") String smeId,
            @Valid ItemStatusUpdate statusUpdate) {
        log.info("Updating item '{}' status to '{}' by SME '{}' in queue: '{}'", 
                itemId, statusUpdate.status(), smeId, queueId);
        
        queueItemService.updateItemStatus(itemId, statusUpdate.status(), smeId);
        return Response.ok().build();
    }

    // DTOs for public API
    public record AnnotationCreate(
            @Schema(description = "Annotation metrics as JSON object")
            java.util.Map<String, Object> metrics,
            
            @Schema(description = "Optional comment")
            String comment
    ) {}

    public record ItemStatusUpdate(
            @Schema(description = "New status for the item")
            QueueItem.QueueItemStatus status
    ) {}
}