package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueCreate;
import com.comet.opik.api.Page;
import com.comet.opik.api.error.ErrorMessage;
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
import jakarta.ws.rs.DELETE;
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

import java.util.UUID;

@Path("/v1/private/annotation-queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Annotation Queues", description = "Human feedback annotation queue operations")
public class AnnotationQueuesResource {

    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(
            operationId = "getAnnotationQueues",
            summary = "Get annotation queues",
            description = "Get annotation queues for a workspace with pagination",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Annotation queues retrieved successfully",
                            content = @Content(schema = @Schema(implementation = Page.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    @JsonView(AnnotationQueue.View.Public.class)
    @RateLimited
    public Response getAnnotationQueues(
            @QueryParam("workspaceName") String workspaceName,
            @QueryParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @Context UriInfo uriInfo) {

        log.info("Getting annotation queues for workspace '{}', project '{}', page '{}', size '{}'", 
                workspaceName, projectId, page, size);

        // For now, we'll use a default project ID if none provided
        // TODO: Implement proper workspace-level queues or default project resolution
        UUID resolvedProjectId = projectId != null ? projectId : UUID.randomUUID(); // Temporary

        var queues = annotationQueueService.getQueues(resolvedProjectId, page, size);

        // Add share URLs to queues
        var queuesWithUrls = queues.toBuilder()
                .content(queues.content().stream()
                        .map(queue -> queue.toBuilder()
                                .shareUrl(annotationQueueService.generateShareUrl(queue.id()))
                                .build())
                        .toList())
                .build();

        return Response.ok(queuesWithUrls).build();
    }

    @GET
    @Path("/{queue_id}")
    @Operation(
            operationId = "getAnnotationQueue",
            summary = "Get annotation queue",
            description = "Get a specific annotation queue by ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Annotation queue retrieved successfully",
                            content = @Content(schema = @Schema(implementation = AnnotationQueue.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "404", description = "Annotation queue not found")
            }
    )
    @JsonView(AnnotationQueue.View.Public.class)
    @RateLimited
    public Response getAnnotationQueue(
            @PathParam("queue_id") UUID queueId,
            @QueryParam("workspaceName") String workspaceName,
            @QueryParam("projectId") UUID projectId) {

        log.info("Getting annotation queue '{}' for workspace '{}', project '{}'", queueId, workspaceName, projectId);

        // For now, we'll use a default project ID if none provided
        UUID resolvedProjectId = projectId != null ? projectId : UUID.randomUUID(); // Temporary

        var queue = annotationQueueService.getQueue(queueId, resolvedProjectId);
        var queueWithUrl = queue.toBuilder()
                .shareUrl(annotationQueueService.generateShareUrl(queue.id()))
                .build();

        return Response.ok(queueWithUrl).build();
    }

    @POST
    @Operation(
            operationId = "createAnnotationQueue",
            summary = "Create annotation queue",
            description = "Create a new annotation queue for human feedback collection",
            requestBody = @RequestBody(
                    description = "Annotation queue creation request",
                    content = @Content(schema = @Schema(implementation = AnnotationQueueCreateWithWorkspace.class))
            ),
            responses = {
                    @ApiResponse(responseCode = "201", description = "Annotation queue created successfully",
                            content = @Content(schema = @Schema(implementation = AnnotationQueue.class)),
                            headers = @Header(name = "Location", description = "URL of the created annotation queue")),
                    @ApiResponse(responseCode = "400", description = "Invalid request",
                            content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    @JsonView(AnnotationQueue.View.Public.class)
    @RateLimited
    public Response createAnnotationQueue(
            @Valid AnnotationQueueCreateWithWorkspace request,
            @Context UriInfo uriInfo) {

        log.info("Creating annotation queue '{}' for workspace '{}', project '{}'", 
                request.name(), request.workspaceName(), request.projectId());

        // For now, we'll use a default project ID if none provided
        UUID resolvedProjectId = request.projectId() != null ? request.projectId() : UUID.randomUUID(); // Temporary

        var userId = requestContext.get().getUserId();
        var queueCreate = AnnotationQueueCreate.builder()
                .name(request.name())
                .description(request.description())
                .templateId(request.templateId())
                .visibleFields(request.visibleFields())
                .requiredMetrics(request.requiredMetrics())
                .optionalMetrics(request.optionalMetrics())
                .instructions(request.instructions())
                .dueDate(request.dueDate())
                .build();

        var queue = annotationQueueService.createQueue(queueCreate, resolvedProjectId, userId);
        var queueWithUrl = queue.toBuilder()
                .shareUrl(annotationQueueService.generateShareUrl(queue.id()))
                .build();

        var location = uriInfo.getAbsolutePathBuilder()
                .path(queue.id().toString())
                .build();

        return Response.status(Response.Status.CREATED)
                .entity(queueWithUrl)
                .location(location)
                .build();
    }

    @PUT
    @Path("/{queue_id}")
    @Operation(
            operationId = "updateAnnotationQueue",
            summary = "Update annotation queue",
            description = "Update an existing annotation queue",
            requestBody = @RequestBody(
                    description = "Annotation queue update request",
                    content = @Content(schema = @Schema(implementation = AnnotationQueueCreateWithWorkspace.class))
            ),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Annotation queue updated successfully",
                            content = @Content(schema = @Schema(implementation = AnnotationQueue.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid request",
                            content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "404", description = "Annotation queue not found")
            }
    )
    @JsonView(AnnotationQueue.View.Public.class)
    @RateLimited
    public Response updateAnnotationQueue(
            @PathParam("queue_id") UUID queueId,
            @Valid AnnotationQueueCreateWithWorkspace request) {

        log.info("Updating annotation queue '{}' for workspace '{}', project '{}'", 
                queueId, request.workspaceName(), request.projectId());

        // For now, we'll use a default project ID if none provided
        UUID resolvedProjectId = request.projectId() != null ? request.projectId() : UUID.randomUUID(); // Temporary

        var queueCreate = AnnotationQueueCreate.builder()
                .name(request.name())
                .description(request.description())
                .templateId(request.templateId())
                .visibleFields(request.visibleFields())
                .requiredMetrics(request.requiredMetrics())
                .optionalMetrics(request.optionalMetrics())
                .instructions(request.instructions())
                .dueDate(request.dueDate())
                .build();

        var queue = annotationQueueService.updateQueue(queueId, resolvedProjectId, queueCreate);
        var queueWithUrl = queue.toBuilder()
                .shareUrl(annotationQueueService.generateShareUrl(queue.id()))
                .build();

        return Response.ok(queueWithUrl).build();
    }

    @DELETE
    @Path("/{queue_id}")
    @Operation(
            operationId = "deleteAnnotationQueue",
            summary = "Delete annotation queue",
            description = "Delete an annotation queue and all its items",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Annotation queue deleted successfully"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "404", description = "Annotation queue not found")
            }
    )
    @RateLimited
    public Response deleteAnnotationQueue(
            @PathParam("queue_id") UUID queueId,
            @QueryParam("workspaceName") String workspaceName,
            @QueryParam("projectId") UUID projectId) {

        log.info("Deleting annotation queue '{}' for workspace '{}', project '{}'", queueId, workspaceName, projectId);

        // For now, we'll use a default project ID if none provided
        UUID resolvedProjectId = projectId != null ? projectId : UUID.randomUUID(); // Temporary

        annotationQueueService.deleteQueue(queueId, resolvedProjectId);

        return Response.noContent().build();
    }

    // Helper record for workspace-aware requests
    public record AnnotationQueueCreateWithWorkspace(
            String workspaceName,
            UUID projectId,
            String name,
            String description,
            UUID templateId,
            java.util.List<String> visibleFields,
            java.util.List<String> requiredMetrics,
            java.util.List<String> optionalMetrics,
            String instructions,
            java.time.Instant dueDate
    ) {}
}