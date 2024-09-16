package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Trace.TracePage;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceSearchCriteria;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.comet.opik.utils.ValidationUtils.validateProjectNameAndProjectId;

@Path("/v1/private/traces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "Traces", description = "Trace related resources")
public class TraceResource {

    private final @NonNull TraceService service;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getTracesByProject", summary = "Get traces by project_name or project_id", description = "Get traces by project_name or project_id", responses = {
            @ApiResponse(responseCode = "200", description = "Trace resource", content = @Content(schema = @Schema(implementation = TracePage.class)))})
    @JsonView(Trace.View.Public.class)
    public Response getByProjectId(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("filters") String filters) {

        validateProjectNameAndProjectId(projectName, projectId);
        var traceFilters = filtersFactory.newFilters(filters, TraceFilter.LIST_TYPE_REFERENCE);
        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Get traces by '{}' on workspaceId '{}'", searchCriteria, workspaceId);

        TracePage tracePage = service.find(page, size, searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found traces by '{}', count '{}' on workspaceId '{}'", searchCriteria, tracePage.size(), workspaceId);

        return Response.ok(tracePage).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getTraceById", summary = "Get trace by id", description = "Get trace by id", responses = {
            @ApiResponse(responseCode = "200", description = "Trace resource", content = @Content(schema = @Schema(implementation = Trace.class)))})
    @JsonView(Trace.View.Public.class)
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting trace by id '{}' on workspace_id '{}'", id, workspaceId);

        Trace trace = service.get(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got trace by id '{}', projectId '{}' on workspace_id '{}'", trace.id(), trace.projectId(),
                workspaceId);

        return Response.ok(trace).build();
    }

    @POST
    @Operation(operationId = "createTrace", summary = "Create trace", description = "Get trace", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{traceId}", schema = @Schema(implementation = String.class))})})
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Trace.class))) @JsonView(Trace.View.Write.class) @NotNull @Valid Trace trace,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating trace with id '{}', projectName '{}' on workspace_id '{}'",
                trace.id(), trace.projectName(), workspaceId);

        var id = service.create(trace)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created trace with id '{}', projectName '{}' on workspace_id '{}'",
                id, trace.projectName(), workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();

        return Response.created(uri).build();
    }

    @POST
    @Path("/batch")
    @Operation(operationId = "createTraces", summary = "Create traces", description = "Create traces", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response createSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceBatch.class))) @JsonView(Trace.View.Write.class) @NotNull @Valid TraceBatch traces) {

        traces.traces()
                .stream()
                .filter(trace -> trace.id() != null) // Filter out spans with null IDs
                .collect(Collectors.groupingBy(Trace::id))
                .forEach((id, traceGroup) -> {
                    if (traceGroup.size() > 1) {
                        throw new ClientErrorException("Duplicate trace id '%s'".formatted(id), 422);
                    }
                });

        service.create(traces)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateTrace", summary = "Update trace by id", description = "Update trace by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceUpdate.class))) @Valid @NonNull TraceUpdate trace) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating span with id '{}' on workspaceId '{}'", id, workspaceId);

        service.update(trace, id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updated span with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteTraceById", summary = "Delete trace by id", description = "Delete trace by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteById(@PathParam("id") UUID id) {

        log.info("Deleting trace with id '{}'", id);

        service.delete(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted trace with id '{}'", id);

        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}/feedback-scores")
    @Operation(operationId = "addTraceFeedbackScore", summary = "Add trace feedback score", description = "Add trace feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response addTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScore.class))) @NotNull @Valid FeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Add span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        feedbackScoreService.scoreTrace(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Added span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/feedback-scores/delete")
    @Operation(operationId = "deleteTraceFeedbackScore", summary = "Delete trace feedback score", description = "Delete trace feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteFeedbackScore.class))) @NotNull @Valid DeleteFeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Delete span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        feedbackScoreService.deleteTraceScore(id, score.name())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        return Response.noContent().build();
    }

    @PUT
    @Path("/feedback-scores")
    @Operation(operationId = "scoreBatchOfTraces", summary = "Batch feedback scoring for traces", description = "Batch feedback scoring for traces", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response scoreBatchOfTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScoreBatch.class))) @NotNull @Valid FeedbackScoreBatch batch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Feedback scores batch for traces, size {} on  workspaceId '{}'", batch.scores().size(), workspaceId);

        feedbackScoreService.scoreBatchOfTraces(batch.scores())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Feedback scores batch for traces, size {} on  workspaceId '{}'", batch.scores().size(), workspaceId);

        return Response.noContent().build();
    }

}
