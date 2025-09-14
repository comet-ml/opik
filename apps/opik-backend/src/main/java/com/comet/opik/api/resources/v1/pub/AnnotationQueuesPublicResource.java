package com.comet.opik.api.resources.v1.pub;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/v1/public/annotation-queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "AnnotationQueuesPublic", description = "Public Annotation Queue resources for SME access")
public class AnnotationQueuesPublicResource {

    private final @NonNull AnnotationQueueService annotationQueueService;

    @GET
    @Path("/{shareToken}")
    @Operation(operationId = "getAnnotationQueueByShareToken", summary = "Get annotation queue by share token", description = "Get annotation queue accessible via share token", responses = {
            @ApiResponse(responseCode = "200", description = "Annotation queue resource", content = @Content(schema = @Schema(implementation = AnnotationQueue.class)))
    })
    @JsonView(AnnotationQueue.View.Public.class)
    @RateLimited
    public Response getAnnotationQueueByShareToken(@PathParam("shareToken") UUID shareToken) {

        log.info("Finding annotation queue by share token");

        var annotationQueue = annotationQueueService.findByShareToken(shareToken)
                .block();

        log.info("Found annotation queue by share token");

        return Response.ok().entity(annotationQueue).build();
    }

    @GET
    @Path("/{shareToken}/items")
    @Operation(operationId = "getAnnotationQueueItemsByShareToken", summary = "Get annotation queue items by share token", description = "Get traces or threads in annotation queue via share token", responses = {
            @ApiResponse(responseCode = "200", description = "Retrieved annotation queue items")
    })
    @RateLimited
    public Response getAnnotationQueueItemsByShareToken(
            @PathParam("shareToken") UUID shareToken,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        log.info("Getting items for annotation queue with share token");

        // First verify the queue exists and is public
        var queue = annotationQueueService.findByShareToken(shareToken)
                .block();

        // Get real items from the database
        var items = annotationQueueService.getItemsForPublicAccess(queue.id(), queue.workspaceId(), page, size)
                .block();

        var totalCount = annotationQueueService.getItemsCountForPublicAccess(queue.id(), queue.workspaceId())
                .block();

        var response = Map.of(
                "content", items,
                "total", totalCount,
                "sortable_by", List.of());

        log.info("Retrieved '{}' items for annotation queue with share token",
                items.size());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{shareToken}/progress")
    @Operation(operationId = "getAnnotationQueueProgress", summary = "Get annotation queue progress", description = "Get completion progress for annotation queue", responses = {
            @ApiResponse(responseCode = "200", description = "Progress information")
    })
    @RateLimited
    public Response getAnnotationQueueProgress(@PathParam("shareToken") UUID shareToken,
            @QueryParam("sme_id") String smeId) {

        log.info("Getting progress for annotation queue with share token");

        // First verify the queue exists and is public
        var queue = annotationQueueService.findByShareToken(shareToken)
                .block();

        Map<String, Object> response;
        if (smeId != null && !smeId.trim().isEmpty()) {
            // Get SME-specific progress
            var smeProgress = annotationQueueService.getSMEProgress(queue.id(), queue.workspaceId(), smeId)
                    .block();
            response = Map.of(
                    "total_items", smeProgress.totalItems(),
                    "completed_items", smeProgress.completedItems(),
                    "progress_percentage", smeProgress.completionPercentage());
        } else {
            // Get general queue progress
            response = annotationQueueService.getQueueProgress(queue.id(), queue.workspaceId())
                    .block();
        }

        log.info("Retrieved progress for annotation queue with share token");

        return Response.ok(response).build();
    }

    @GET
    @Path("/{shareToken}/items/{itemId}/data")
    @Operation(operationId = "getAnnotationQueueItemData", summary = "Get annotation queue item data", description = "Get full trace or thread data for annotation", responses = {
            @ApiResponse(responseCode = "200", description = "Item data retrieved successfully")
    })
    @RateLimited
    public Response getAnnotationQueueItemData(
            @PathParam("shareToken") UUID shareToken,
            @PathParam("itemId") UUID itemId) {

        log.info("Getting data for item '{}' in queue with share token", itemId);

        // First verify the queue exists and is public
        var queue = annotationQueueService.findByShareToken(shareToken)
                .block();

        // Get item data based on queue scope
        var itemData = annotationQueueService
                .getItemDataForAnnotation(queue.id(), queue.workspaceId(), itemId, queue.scope())
                .block();

        log.info("Successfully retrieved data for item '{}' in queue with share token", itemId);

        return Response.ok(itemData).build();
    }

    @GET
    @Path("/{shareToken}/progress/{smeId}")
    @Operation(operationId = "getSMEProgress", summary = "Get SME individual progress", description = "Get individual SME progress and next item", responses = {
            @ApiResponse(responseCode = "200", description = "SME progress retrieved successfully")
    })
    @RateLimited
    public Response getSMEProgress(
            @PathParam("shareToken") UUID shareToken,
            @PathParam("smeId") String smeId) {

        log.info("Getting progress for SME '{}' in queue with share token", smeId);

        // First verify the queue exists and is public
        var queue = annotationQueueService.findByShareToken(shareToken)
                .block();

        // Get SME-specific progress
        var progress = annotationQueueService.getSMEProgress(queue.id(), queue.workspaceId(), smeId)
                .block();

        log.info("Successfully retrieved progress for SME '{}' in queue with share token", smeId);

        return Response.ok(progress).build();
    }

    @GET
    @Path("/{shareToken}/next-item/{smeId}")
    @Operation(operationId = "getNextItemForSME", summary = "Get next item for SME", description = "Get next unprocessed item for specific SME", responses = {
            @ApiResponse(responseCode = "200", description = "Next item retrieved successfully"),
            @ApiResponse(responseCode = "204", description = "No more items available")
    })
    @RateLimited
    public Response getNextItemForSME(
            @PathParam("shareToken") UUID shareToken,
            @PathParam("smeId") String smeId) {

        log.info("Getting next item for SME '{}' in queue with share token", smeId);

        // First verify the queue exists and is public
        var queue = annotationQueueService.findByShareToken(shareToken)
                .block();

        // Get next item for SME
        var nextItem = annotationQueueService.getNextItemForSME(queue.id(), smeId)
                .block();

        if (nextItem == null) {
            log.info("No more items available for SME '{}' in queue with share token", smeId);
            return Response.noContent().build();
        }

        log.info("Successfully retrieved next item for SME '{}' in queue with share token", smeId);

        return Response.ok(nextItem).build();
    }

    @POST
    @Path("/{shareToken}/items/{itemId}/annotate")
    @Operation(operationId = "submitAnnotation", summary = "Submit annotation for queue item", description = "Submit feedback scores and comments for a queue item", responses = {
            @ApiResponse(responseCode = "204", description = "Annotation submitted successfully")
    })
    @RateLimited
    public Response submitAnnotation(
            @PathParam("shareToken") UUID shareToken,
            @PathParam("itemId") UUID itemId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationSubmission.class))) @Valid AnnotationSubmission request) {

        log.info("Submitting annotation for item '{}' in queue with share token", itemId);
        log.info("Received request: smeId='{}', feedbackScores={}, comment='{}'",
                request.smeId(), request.feedbackScores(), request.comment());

        try {
            // First verify the queue exists and is public, then submit annotation
            var result = annotationQueueService.findByShareToken(shareToken)
                    .switchIfEmpty(
                            Mono.error(new WebApplicationException("Annotation queue not found or inaccessible", 404)))
                    .flatMap(queue -> {
                        log.info("Found queue '{}' for share token, submitting annotation", queue.id());
                        return annotationQueueService.submitAnnotationWithQueue(queue, itemId, request.smeId(),
                                request.feedbackScores(), request.comment());
                    })
                    .doOnSuccess(
                            v -> log.info("Successfully submitted annotation for item '{}' in queue with share token",
                                    itemId))
                    .doOnError(error -> log.error(
                            "Failed to submit annotation for item '{}' in queue with share token: {}",
                            itemId, error.getMessage(), error))
                    .block(); // Temporarily use block to test

            return Response.noContent().build();
        } catch (Exception e) {
            log.error("Error submitting annotation: {}", e.getMessage(), e);
            throw new WebApplicationException("Failed to submit annotation", 500);
        }
    }

    // DTO for annotation submission
    public record AnnotationSubmission(
            @NotBlank String smeId,
            @Valid List<FeedbackScore> feedbackScores,
            String comment) {
    }
}
