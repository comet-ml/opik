package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.Trace;
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
            @ApiResponse(responseCode = "200", description = "Trace resource", content = @Content(schema = @Schema(implementation = Trace.TracePage.class)))})
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
        return service.find(page, size, searchCriteria)
                .map(tracesPage -> Response.ok(tracesPage).build())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getTraceById", summary = "Get trace by id", description = "Get trace by id", responses = {
            @ApiResponse(responseCode = "200", description = "Trace resource", content = @Content(schema = @Schema(implementation = Trace.class)))})
    @JsonView(Trace.View.Public.class)
    public Response getById(@PathParam("id") UUID id) {

        return service.get(id)
                .map(trace -> Response.ok(trace).build())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
    }

    @POST
    @Operation(operationId = "createTrace", summary = "Create trace", description = "Get trace", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{traceId}", schema = @Schema(implementation = String.class))})})
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Trace.class))) @JsonView(Trace.View.Write.class) @NotNull @Valid Trace trace,
            @Context UriInfo uriInfo) {

        var id = service.create(trace)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        return Response.created(uri).build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateTrace", summary = "Update trace by id", description = "Update trace by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceUpdate.class))) @Valid @NonNull TraceUpdate trace) {

        service.update(trace, id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteTraceById", summary = "Delete trace by id", description = "Delete trace by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteById(@PathParam("id") UUID id) {

        service.delete(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}/feedback-scores")
    @Operation(operationId = "addTraceFeedbackScore", summary = "Add trace feedback score", description = "Add trace feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response addTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScore.class))) @NotNull @Valid FeedbackScore score) {

        feedbackScoreService.scoreTrace(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/feedback-scores/delete")
    @Operation(operationId = "deleteTraceFeedbackScore", summary = "Delete trace feedback score", description = "Delete trace feedback score", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteFeedbackScore.class))) @NotNull @Valid DeleteFeedbackScore score) {

        feedbackScoreService.deleteTraceScore(id, score.name())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @PUT
    @Path("/feedback-scores")
    @Operation(operationId = "scoreBatchOfTraces", summary = "Batch feedback scoring for traces", description = "Batch feedback scoring for traces", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response scoreBatchOfTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScoreBatch.class))) @NotNull @Valid FeedbackScoreBatch batch) {

        feedbackScoreService.scoreBatchOfTraces(batch.scores())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        return Response.noContent().build();
    }

}
