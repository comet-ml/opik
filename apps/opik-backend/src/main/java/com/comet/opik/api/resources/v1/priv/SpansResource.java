package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanSearchCriteria;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SpanSortingFactory;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.Streamer;
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
import jakarta.inject.Inject;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.Span.SpanField;
import static com.comet.opik.api.Span.SpanPage;
import static com.comet.opik.api.Span.View;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.comet.opik.utils.ValidationUtils.validateProjectNameAndProjectId;

@Path("/v1/private/spans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Spans", description = "Span related resources")
public class SpansResource {

    private final @NonNull SpanService spanService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull CommentService commentService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull SpanSortingFactory sortingFactory;

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull Streamer streamer;

    @GET
    @Operation(operationId = "getSpansByProject", summary = "Get spans by project_name or project_id and optionally by trace_id and/or type", description = "Get spans by project_name or project_id and optionally by trace_id and/or type", responses = {
            @ApiResponse(responseCode = "200", description = "Spans resource", content = @Content(schema = @Schema(implementation = SpanPage.class)))})
    @JsonView(View.Public.class)
    @RateLimited(value = "getSpans:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getSpansByProject(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("trace_id") UUID traceId,
            @QueryParam("type") SpanType type,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate,
            @QueryParam("sorting") String sorting,
            @QueryParam("exclude") String exclude) {

        validateProjectNameAndProjectId(projectName, projectId);
        var spanFilters = filtersFactory.newFilters(filters, SpanFilter.LIST_TYPE_REFERENCE);
        var sortingFields = sortingFactory.newSorting(sorting);

        WorkspaceMetadata workspaceMetadata = workspaceMetadataService
                .getWorkspaceMetadata(requestContext.get().getWorkspaceId())
                .block();

        if (!sortingFields.isEmpty() && !workspaceMetadata.canUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var spanSearchCriteria = SpanSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .traceId(traceId)
                .type(type)
                .filters(spanFilters)
                .truncate(truncate)
                .sortingFields(sortingFields)
                .exclude(ParamsValidator.get(exclude, SpanField.class, "exclude"))
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Get spans by '{}' on workspaceId '{}'", spanSearchCriteria, workspaceId);
        SpanPage spans = spanService.find(page, size, spanSearchCriteria)
                .map(it -> {
                    // Remove sortableBy fields if dynamic sorting is disabled due to workspace size
                    if (!workspaceMetadata.canUseDynamicSorting()) {
                        return it.toBuilder().sortableBy(List.of()).build();
                    }
                    return it;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found spans by '{}', count '{}' on workspaceId '{}'", spanSearchCriteria, spans.size(), workspaceId);
        return Response.ok().entity(spans).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getSpanById", summary = "Get span by id", description = "Get span by id", responses = {
            @ApiResponse(responseCode = "200", description = "Span resource", content = @Content(schema = @Schema(implementation = Span.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = Span.class)))})
    @JsonView(View.Public.class)
    @RateLimited(value = "getSpanById:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getById(@PathParam("id") @NotNull UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting span by id '{}' on workspace_id '{}'", id, workspaceId);
        var span = spanService.getById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Got span by id '{}', traceId '{}', parentSpanId '{}' on workspace_id '{}'", span.id(), span.traceId(),
                span.parentSpanId(), workspaceId);

        return Response.ok().entity(span).build();
    }

    @POST
    @Operation(operationId = "createSpan", summary = "Create span", description = "Create span", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/spans/{spanId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = com.comet.opik.api.error.ErrorMessage.class)))})
    @RateLimited
    @UsageLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Span.class))) @JsonView(View.Write.class) @NotNull @Valid Span span,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating span with id '{}', projectName '{}', traceId '{}', parentSpanId '{}', workspaceId '{}'",
                span.id(), span.projectName(), span.traceId(), span.parentSpanId(), workspaceId);
        var id = spanService.create(span)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("Created span with id '{}', projectName '{}', traceId '{}', parentSpanId '{}', workspaceId '{}'",
                id, span.projectName(), span.traceId(), span.parentSpanId(), workspaceId);
        return Response.created(uri).build();
    }

    @POST
    @Path("/batch")
    @Operation(operationId = "createSpans", summary = "Create spans", description = "Create spans", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    @RateLimited
    @UsageLimited
    public Response createSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanBatch.class))) @JsonView(View.Write.class) @NotNull @Valid SpanBatch spans) {

        spans.spans()
                .stream()
                .filter(span -> span.id() != null) // Filter out spans with null IDs
                .collect(Collectors.groupingBy(Span::id))
                .forEach((id, spanGroup) -> {
                    if (spanGroup.size() > 1) {
                        throw new ClientErrorException("Duplicate span id '%s'".formatted(id), 422);
                    }
                });

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating spans batch with size '{}' on workspaceId '{}'", spans.spans().size(), workspaceId);

        spanService.create(spans)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created spans batch with size '{}' on workspaceId '{}'", spans.spans().size(), workspaceId);

        return Response.noContent().build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateSpan", summary = "Update span by id", description = "Update span by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not found")})
    @RateLimited
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanUpdate.class))) @NotNull @Valid SpanUpdate spanUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating span with id '{}' on workspaceId '{}'", id, workspaceId);
        spanService.update(id, spanUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Updated span with id '{}' on workspaceId '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteSpanById", summary = "Delete span by id", description = "Delete span by id", responses = {
            @ApiResponse(responseCode = "501", description = "Not implemented"),
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteById(@PathParam("id") @NotNull String id) {

        log.info("Deleting span with id '{}' on workspaceId '{}'", id, requestContext.get().getWorkspaceId());
        return Response.status(501).build();
    }

    @PUT
    @Path("/{id}/feedback-scores")
    @Operation(operationId = "addSpanFeedbackScore", summary = "Add span feedback score", description = "Add span feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    @RateLimited
    public Response addSpanFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScore.class))) @NotNull @Valid FeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Add span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);
        feedbackScoreService.scoreSpan(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Added span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/feedback-scores/delete")
    @Operation(operationId = "deleteSpanFeedbackScore", summary = "Delete span feedback score", description = "Delete span feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteSpanFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteFeedbackScore.class))) @NotNull @Valid DeleteFeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Delete span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);
        feedbackScoreService.deleteSpanScore(id, score.name())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted span feedback score '{}' for id '{}' on workspaceId '{}'", score.name(), id, workspaceId);
        return Response.noContent().build();
    }

    @PUT
    @Path("/feedback-scores")
    @Operation(operationId = "scoreBatchOfSpans", summary = "Batch feedback scoring for spans", description = "Batch feedback scoring for spans", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    @RateLimited
    public Response scoreBatchOfSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScoreBatch.class))) @NotNull @Valid FeedbackScoreBatch batch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Feedback scores batch for spans, size {} on  workspaceId '{}'", batch.scores().size(), workspaceId);
        feedbackScoreService.scoreBatchOfSpans(batch.scores())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();
        log.info("Scored batch for spans, size {} on workspaceId '{}'", batch.scores().size(), workspaceId);
        return Response.noContent().build();
    }

    @GET
    @Path("/stats")
    @Operation(operationId = "getSpanStats", summary = "Get span stats", description = "Get span stats", responses = {
            @ApiResponse(responseCode = "200", description = "Span stats resource", content = @Content(schema = @Schema(implementation = ProjectStats.class)))
    })
    @JsonView({ProjectStats.ProjectStatItem.View.Public.class})
    public Response getStats(@QueryParam("project_id") UUID projectId,
            @QueryParam("project_name") String projectName,
            @QueryParam("trace_id") UUID traceId,
            @QueryParam("type") SpanType type,
            @QueryParam("filters") String filters) {

        validateProjectNameAndProjectId(projectName, projectId);
        var spanFilters = filtersFactory.newFilters(filters, SpanFilter.LIST_TYPE_REFERENCE);
        var searchCriteria = SpanSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(spanFilters)
                .traceId(traceId)
                .type(type)
                .sortingFields(List.of())
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Get span stats by '{}' on workspaceId '{}'", searchCriteria, workspaceId);

        ProjectStats projectStats = spanService.getStats(searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found span stats by '{}', count '{}' on workspaceId '{}'", searchCriteria,
                projectStats.stats().size(), workspaceId);

        return Response.ok(projectStats).build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "Find Feedback Score names", description = "Find Feedback Score names", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback Scores resource", content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(@QueryParam("project_id") UUID projectId,
            @QueryParam("type") SpanType type) {

        if (projectId == null) {
            throw new BadRequestException("project_id must be provided");
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find feedback score names by project_id '{}', on workspaceId '{}'",
                projectId, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getSpanFeedbackScoreNames(projectId, type)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found feedback score names '{}' by project_id '{}', on workspaceId '{}'",
                feedbackScoreNames.scores().size(), projectId, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "searchSpans", summary = "Search spans", description = "Search spans", responses = {
            @ApiResponse(responseCode = "200", description = "Spans stream or error during process", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    Span.class,
                    ErrorMessage.class
            }), maxItems = 2000))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @JsonView(View.Public.class)
    @RateLimited(value = "search_spans:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public ChunkedOutput<JsonNode> searchSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanSearchStreamRequest.class))) @NotNull @Valid SpanSearchStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        validateProjectNameAndProjectId(request.projectName(), request.projectId());

        log.info("Streaming spans search results by '{}', workspaceId '{}'", request, workspaceId);
        var criteria = SpanSearchCriteria.builder()
                .lastReceivedSpanId(request.lastRetrievedId())
                .truncate(request.truncate())
                .traceId(request.traceId())
                .type(request.type())
                .projectName(request.projectName())
                .projectId(request.projectId())
                .filters(filtersFactory.validateFilter(request.filters()))
                .sortingFields(List.of())
                .build();

        var items = spanService.search(request.limit(), criteria)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));

        return streamer.getOutputStream(items,
                () -> log.info("Streamed spans search results by '{}', workspaceId '{}'", request, workspaceId));
    }

    @POST
    @Path("/{id}/comments")
    @Operation(operationId = "addSpanComment", summary = "Add span comment", description = "Add span comment", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/spans/{spanId}/comments/{commentId}", schema = @Schema(implementation = String.class))})})
    public Response addSpanComment(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Add comment for span with id '{}' on workspaceId '{}'", id, workspaceId);

        var commentId = commentService.create(id, comment, CommentDAO.EntityType.SPAN)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(commentId)).build();
        log.info("Added comment with id '{}' for span with id '{}' on workspaceId '{}'", comment.id(), id,
                workspaceId);

        return Response.created(uri).build();
    }

    @GET
    @Path("/{spanId}/comments/{commentId}")
    @Operation(operationId = "getSpanComment", summary = "Get span comment", description = "Get span comment", responses = {
            @ApiResponse(responseCode = "200", description = "Comment resource", content = @Content(schema = @Schema(implementation = Comment.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getSpanComment(@PathParam("commentId") @NotNull UUID commentId,
            @PathParam("spanId") @NotNull UUID spanId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting span comment by id '{}' on workspace_id '{}'", commentId, workspaceId);

        Comment comment = commentService.get(spanId, commentId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got span comment by id '{}', on workspace_id '{}'", comment.id(), workspaceId);

        return Response.ok(comment).build();
    }

    @PATCH
    @Path("/comments/{commentId}")
    @Operation(operationId = "updateSpanComment", summary = "Update span comment by id", description = "Update span comment by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not found")})
    public Response updateSpanComment(@PathParam("commentId") UUID commentId,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Update span comment with id '{}' on workspaceId '{}'", commentId, workspaceId);

        commentService.update(commentId, comment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updated span comment with id '{}' on workspaceId '{}'", commentId, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/comments/delete")
    @Operation(operationId = "deleteSpanComments", summary = "Delete span comments", description = "Delete span comments", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
    })
    public Response deleteSpanComments(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Delete span comments with ids '{}' on workspaceId '{}'", batchDelete.ids(), workspaceId);

        commentService.delete(batchDelete)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted span comments with ids '{}' on workspaceId '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }
}
