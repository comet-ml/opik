package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.domain.evaluators.ManualEvaluationService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

/**
 * REST resource for manually triggering evaluation rules on traces, threads, and spans.
 * Allows users to run online evaluation metrics on specific entities without sampling,
 * directly from the UI.
 */
@Path("/v1/private/manual-evaluation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "Manual Evaluation", description = "Manual evaluation resources for traces, threads, and spans")
public class ManualEvaluationResource {

    private final @NonNull ManualEvaluationService manualEvaluationService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/traces")
    @Operation(operationId = "evaluateTraces", summary = "Manually evaluate traces", description = "Manually trigger evaluation rules on selected traces. Bypasses sampling and enqueues all specified traces for evaluation.", responses = {
            @ApiResponse(responseCode = "202", description = "Accepted - Evaluation request queued successfully", content = @Content(schema = @Schema(implementation = ManualEvaluationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid request or missing automation rules", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - Project not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response evaluateTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = ManualEvaluationRequest.class))) @Valid @NonNull ManualEvaluationRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        log.info(
                "Manual evaluation request for '{}' traces with '{}' rules in project '{}', workspace '{}' by user '{}'",
                request.entityIds().size(), request.ruleIds().size(), request.projectId(), workspaceId, userName);

        var response = manualEvaluationService.evaluate(request, request.projectId(), workspaceId, userName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Manual evaluation request accepted for '{}' traces in project '{}', workspace '{}'",
                request.entityIds().size(), request.projectId(), workspaceId);

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

    @POST
    @Path("/threads")
    @Operation(operationId = "evaluateThreads", summary = "Manually evaluate threads", description = "Manually trigger evaluation rules on selected threads. Bypasses sampling and enqueues all specified threads for evaluation.", responses = {
            @ApiResponse(responseCode = "202", description = "Accepted - Evaluation request queued successfully", content = @Content(schema = @Schema(implementation = ManualEvaluationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid request or missing automation rules", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - Project not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response evaluateThreads(
            @RequestBody(content = @Content(schema = @Schema(implementation = ManualEvaluationRequest.class))) @Valid @NonNull ManualEvaluationRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        log.info(
                "Manual evaluation request for '{}' threads with '{}' rules in project '{}', workspace '{}' by user '{}'",
                request.entityIds().size(), request.ruleIds().size(), request.projectId(), workspaceId, userName);

        var response = manualEvaluationService.evaluate(request, request.projectId(), workspaceId, userName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Manual evaluation request accepted for '{}' threads in project '{}', workspace '{}'",
                request.entityIds().size(), request.projectId(), workspaceId);

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

    @POST
    @Path("/spans")
    @Operation(operationId = "evaluateSpans", summary = "Manually evaluate spans", description = "Manually trigger evaluation rules on selected spans. Bypasses sampling and enqueues all specified spans for evaluation.", responses = {
            @ApiResponse(responseCode = "202", description = "Accepted - Evaluation request queued successfully", content = @Content(schema = @Schema(implementation = ManualEvaluationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid request or missing automation rules", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - Project not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response evaluateSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = ManualEvaluationRequest.class))) @Valid @NonNull ManualEvaluationRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        log.info(
                "Manual evaluation request for '{}' spans with '{}' rules in project '{}', workspace '{}' by user '{}'",
                request.entityIds().size(), request.ruleIds().size(), request.projectId(), workspaceId, userName);

        var response = manualEvaluationService.evaluate(request, request.projectId(), workspaceId, userName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Manual evaluation request accepted for '{}' spans in project '{}', workspace '{}'",
                request.entityIds().size(), request.projectId(), workspaceId);

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }
}
