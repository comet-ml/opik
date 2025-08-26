package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Page;
import com.comet.opik.api.QueueItem;
import com.comet.opik.api.QueueItemsBatch;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.QueueItemService;
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Path("/v1/private/queue-items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Queue Items", description = "Annotation queue item operations")
public class QueueItemsResource {

    private final @NonNull QueueItemService queueItemService;
    private final @NonNull AnnotationQueueService annotationQueueService;

    @GET
    @Operation(
            operationId = "getQueueItems",
            summary = "Get queue items",
            description = "Get items in an annotation queue with pagination",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Queue items retrieved successfully",
                            content = @Content(schema = @Schema(implementation = Page.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "404", description = "Queue not found")
            }
    )
    @JsonView(QueueItem.View.Public.class)
    @RateLimited
    public Response getQueueItems(
            @QueryParam("queueId") UUID queueId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("50") int size) {

        log.info("Getting items for queue '{}', page '{}', size '{}'", queueId, page, size);

        var items = queueItemService.getQueueItems(queueId, page, size);
        return Response.ok(items).build();
    }

    @POST
    @Operation(
            operationId = "addItemsToQueue",
            summary = "Add items to queue",
            description = "Add traces or threads to an annotation queue",
            requestBody = @RequestBody(
                    description = "Items to add to the queue",
                    content = @Content(schema = @Schema(implementation = QueueItemsBatch.class))
            ),
            responses = {
                    @ApiResponse(responseCode = "201", description = "Items added successfully"),
                    @ApiResponse(responseCode = "400", description = "Invalid request",
                            content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "404", description = "Queue not found")
            }
    )
    @RateLimited
    public Response addItemsToQueue(
            @QueryParam("queueId") UUID queueId,
            @Valid QueueItemsBatch batch) {

        log.info("Adding '{}' items to queue '{}'", batch.items().size(), queueId);

        var items = queueItemService.addItemsToQueue(queueId, batch);

        return Response.status(Response.Status.CREATED)
                .entity(items)
                .build();
    }

    @DELETE
    @Operation(
            operationId = "removeItemFromQueue",
            summary = "Remove item from queue",
            description = "Remove a specific item from an annotation queue",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Item removed successfully"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "404", description = "Item not found")
            }
    )
    @RateLimited
    public Response removeItemFromQueue(
            @QueryParam("queueId") UUID queueId,
            @QueryParam("itemId") UUID itemId) {

        log.info("Removing item '{}' from queue '{}'", itemId, queueId);

        queueItemService.removeItemFromQueue(queueId, itemId);

        return Response.noContent().build();
    }
}