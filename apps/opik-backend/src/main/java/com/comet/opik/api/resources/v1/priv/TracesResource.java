package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Trace.TracePage;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceSearchCriteria;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadIdentifier;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.Streamer;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.workspaces.WorkspaceMetadata;
import com.comet.opik.domain.workspaces.WorkspaceMetadataService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.infrastructure.usagelimit.UsageLimited;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
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
import org.glassfish.jersey.server.ChunkedOutput;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.comet.opik.utils.ValidationUtils.validateProjectNameAndProjectId;

@Path("/v1/private/traces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "Traces", description = "Trace related resources")
public class TracesResource {

    private final @NonNull TraceService service;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull CommentService commentService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull TraceSortingFactory traceSortingFactory;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull Streamer streamer;
    private final @NonNull ProjectService projectService;

    @GET
    @Operation(operationId = "getTracesByProject", summary = "Get traces by project_name or project_id", description = "Get traces by project_name or project_id", responses = {
            @ApiResponse(responseCode = "200", description = "Trace resource", content = @Content(schema = @Schema(implementation = TracePage.class)))})
    @JsonView(Trace.View.Public.class)
    public Response getTracesByProject(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate,
            @QueryParam("sorting") String sorting,
            @QueryParam("exclude") String exclude) {

        validateProjectNameAndProjectId(projectName, projectId);
        var traceFilters = filtersFactory.newFilters(filters, TraceFilter.LIST_TYPE_REFERENCE);
        var sortingFields = traceSortingFactory.newSorting(sorting);

        WorkspaceMetadata workspaceMetadata = workspaceMetadataService
                .getWorkspaceMetadata(requestContext.get().getWorkspaceId())
                .block();

        if (!sortingFields.isEmpty() && !workspaceMetadata.canUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .truncate(truncate)
                .sortingFields(sortingFields)
                .exclude(ParamsValidator.get(exclude, Trace.TraceField.class, "exclude"))
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Get traces by '{}' on workspaceId '{}'", searchCriteria, workspaceId);

        TracePage tracePage = service.find(page, size, searchCriteria)
                .map(it -> {
                    // Remove sortableBy fields if dynamic sorting is disabled due to workspace size
                    if (!workspaceMetadata.canUseDynamicSorting()) {
                        return it.toBuilder().sortableBy(List.of()).build();
                    }
                    return it;
                })
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

        // Verify project visibility
        projectService.get(trace.projectId());

        log.info("Got trace by id '{}', projectId '{}' on workspace_id '{}'", trace.id(), trace.projectId(),
                workspaceId);

        return Response.ok(trace).build();
    }

    @POST
    @Operation(operationId = "createTrace", summary = "Create trace", description = "Get trace", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{traceId}", schema = @Schema(implementation = String.class))})})
    @RateLimited
    @UsageLimited
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
    @RateLimited
    @UsageLimited
    public Response createTraces(
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
    @RateLimited
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceUpdate.class))) @Valid @NonNull TraceUpdate trace) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating trace with id '{}' on workspaceId '{}'", id, workspaceId);

        service.update(trace, id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updated trace with id '{}' on workspaceId '{}'", id, workspaceId);

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

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteTraces", summary = "Delete traces", description = "Delete traces", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDelete request) {
        log.info("Deleting traces, count '{}'", request.ids().size());
        service.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted traces, count '{}'", request.ids().size());
        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}/feedback-scores")
    @Operation(operationId = "addTraceFeedbackScore", summary = "Add trace feedback score", description = "Add trace feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    @RateLimited
    public Response addTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScore.class))) @NotNull @Valid FeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Add trace feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        feedbackScoreService.scoreTrace(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Added trace feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/comments")
    @Operation(operationId = "addTraceComment", summary = "Add trace comment", description = "Add trace comment", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{traceId}/comments/{commentId}", schema = @Schema(implementation = String.class))})})
    public Response addTraceComment(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Add comment for trace with id '{}' on workspaceId '{}'", id, workspaceId);

        var commentId = commentService.create(id, comment, CommentDAO.EntityType.TRACE)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(commentId)).build();
        log.info("Added comment with id '{}' for trace with id '{}' on workspaceId '{}'", comment.id(), id,
                workspaceId);

        return Response.created(uri).build();
    }

    @GET
    @Path("/{traceId}/comments/{commentId}")
    @Operation(operationId = "getTraceComment", summary = "Get trace comment", description = "Get trace comment", responses = {
            @ApiResponse(responseCode = "200", description = "Comment resource", content = @Content(schema = @Schema(implementation = Comment.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getTraceComment(@PathParam("commentId") @NotNull UUID commentId,
            @PathParam("traceId") @NotNull UUID traceId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting trace comment by id '{}' on workspace_id '{}'", commentId, workspaceId);

        Comment comment = commentService.get(traceId, commentId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got trace comment by id '{}', on workspace_id '{}'", comment.id(), workspaceId);

        return Response.ok(comment).build();
    }

    @PATCH
    @Path("/comments/{commentId}")
    @Operation(operationId = "updateTraceComment", summary = "Update trace comment by id", description = "Update trace comment by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not found")})
    public Response updateTraceComment(@PathParam("commentId") UUID commentId,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Update trace comment with id '{}' on workspaceId '{}'", commentId, workspaceId);

        commentService.update(commentId, comment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updated trace comment with id '{}' on workspaceId '{}'", commentId, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/comments/delete")
    @Operation(operationId = "deleteTraceComments", summary = "Delete trace comments", description = "Delete trace comments", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
    })
    public Response deleteTraceComments(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Delete trace comments with ids '{}' on workspaceId '{}'", batchDelete.ids(), workspaceId);

        commentService.delete(batchDelete)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted trace comments with ids '{}' on workspaceId '{}'", batchDelete.ids(), workspaceId);

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
    @RateLimited
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

    @GET
    @Path("/stats")
    @Operation(operationId = "getTraceStats", summary = "Get trace stats", description = "Get trace stats", responses = {
            @ApiResponse(responseCode = "200", description = "Trace stats resource", content = @Content(schema = @Schema(implementation = ProjectStats.class)))
    })
    @JsonView({ProjectStats.ProjectStatItem.View.Public.class})
    public Response getStats(@QueryParam("project_id") UUID projectId,
            @QueryParam("project_name") String projectName,
            @QueryParam("filters") String filters) {

        validateProjectNameAndProjectId(projectName, projectId);
        var traceFilters = filtersFactory.newFilters(filters, TraceFilter.LIST_TYPE_REFERENCE);
        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Get trace stats by '{}' on workspaceId '{}'", searchCriteria, workspaceId);

        ProjectStats projectStats = service.getStats(searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found trace stats by '{}', count '{}' on workspaceId '{}'", searchCriteria,
                projectStats.stats().size(), workspaceId);

        return Response.ok(projectStats).build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "Find Feedback Score names", description = "Find Feedback Score names", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback Scores resource", content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(@QueryParam("project_id") UUID projectId) {

        if (projectId == null) {
            throw new BadRequestException("project_id must be provided");
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find feedback score names by project_id '{}', on workspaceId '{}'",
                projectId, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getTraceFeedbackScoreNames(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found feedback score names '{}' by project_id '{}', on workspaceId '{}'",
                feedbackScoreNames.scores().size(), projectId, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    @GET
    @Path("/threads")
    @Operation(operationId = "getTraceThreads", summary = "Get trace threads", description = "Get trace threads", responses = {
            @ApiResponse(responseCode = "200", description = "Trace threads resource", content = @Content(schema = @Schema(implementation = TraceThreadPage.class)))})
    public Response getTraceThreads(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("truncate") @Schema(description = "Truncate image included in the messages") boolean truncate,
            @QueryParam("filters") String filters) {

        validateProjectNameAndProjectId(projectName, projectId);
        var traceFilters = filtersFactory.newFilters(filters, TraceThreadFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .truncate(truncate)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Get trace threads by '{}' on workspaceId '{}'", searchCriteria, workspaceId);

        TraceThreadPage traceThreadPage = service.getTraceThreads(page, size, searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found trace threads by '{}', count '{}' on workspaceId '{}'", searchCriteria, traceThreadPage.size(),
                workspaceId);

        return Response.ok(traceThreadPage).build();
    }

    @POST
    @Path("/threads/retrieve")
    @Operation(operationId = "getTraceThread", summary = "Get trace thread", description = "Get trace thread", responses = {
            @ApiResponse(responseCode = "200", description = "Trace thread resource", content = @Content(schema = @Schema(implementation = TraceThread.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getTraceThread(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadIdentifier.class))) @NotNull @Valid TraceThreadIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();

        // Verify project visibility
        projectService.get(identifier.projectId());

        log.info("Getting trace thread by id '{}' and project id '{}' on workspace_id '{}'", identifier.projectId(),
                identifier.threadId(), workspaceId);

        TraceThread thread = service.getThreadById(identifier.projectId(), identifier.threadId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got trace thread by id '{}', project id '{}' on workspace_id '{}'", identifier.threadId(),
                identifier.projectId(), workspaceId);

        return Response.ok(thread).build();
    }

    @POST
    @Path("/threads/delete")
    @Operation(operationId = "deleteTraceThreads", summary = "Delete trace threads", description = "Delete trace threads", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteTraceThreads(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = DeleteTraceThreads.class))) @Valid DeleteTraceThreads traceThreads) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Delete trace threads with project_name '{}' or project_id '{}' on workspaceId '{}'",
                traceThreads.projectName(), traceThreads.projectId(), workspaceId);

        service.deleteTraceThreads(traceThreads)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted trace threads with ids '{}' on workspaceId '{}'", traceThreads.threadIds(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "searchTraces", summary = "Search traces", description = "Search traces", responses = {
            @ApiResponse(responseCode = "200", description = "Traces stream or error during process", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    Trace.class,
                    ErrorMessage.class
            }), maxItems = 2000))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @JsonView(Trace.View.Public.class)
    public ChunkedOutput<JsonNode> searchTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceSearchStreamRequest.class))) @NotNull @Valid TraceSearchStreamRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        validateProjectNameAndProjectId(request.projectName(), request.projectId());

        log.info("Streaming traces search results by '{}', workspaceId '{}'", request, workspaceId);

        var searchCriteria = TraceSearchCriteria.builder()
                .lastReceivedTraceId(request.lastRetrievedId())
                .projectName(request.projectName())
                .projectId(request.projectId())
                .filters(filtersFactory.validateFilter(request.filters()))
                .truncate(request.truncate())
                .sortingFields(List.of())
                .build();

        Flux<Trace> items = service.search(request.limit(), searchCriteria)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));

        return streamer.getOutputStream(items,
                () -> log.info("Streamed traces search results by '{}', workspaceId '{}'", request, workspaceId));
    }

}
