package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.ExperimentItemSearchCriteria;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentItemsDelete;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.resources.v1.priv.validate.ExperimentItemBulkValidator;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentItemBulkIngestionService;
import com.comet.opik.domain.ExperimentItemService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.IdGenerator;
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
import jakarta.ws.rs.Consumes;
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
import org.glassfish.jersey.server.ChunkedOutput;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/experiments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Experiments", description = "Experiment resources")
public class ExperimentsResource {

    private final @NonNull ExperimentService experimentService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Streamer streamer;
    private final @NonNull ExperimentSortingFactory sortingFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull ExperimentItemBulkIngestionService experimentItemBulkIngestionService;

    @GET
    @Operation(operationId = "findExperiments", summary = "Find experiments", description = "Find experiments", responses = {
            @ApiResponse(responseCode = "200", description = "Experiments resource", content = @Content(schema = @Schema(implementation = Experiment.ExperimentPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView(Experiment.View.Public.class)
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("datasetId") UUID datasetId,
            @QueryParam("optimization_id") UUID optimizationId,
            @QueryParam("types") String typesQueryParam,
            @QueryParam("name") String name,
            @QueryParam("dataset_deleted") boolean datasetDeleted,
            @QueryParam("prompt_id") UUID promptId,
            @QueryParam("sorting") String sorting) {

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        WorkspaceMetadata metadata = workspaceMetadataService
                .getWorkspaceMetadata(requestContext.get().getWorkspaceId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        if (!sortingFields.isEmpty() && !metadata.canUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var types = Optional.ofNullable(typesQueryParam)
                .map(queryParam -> ParamsValidator.get(queryParam, ExperimentType.class, "types"))
                .orElse(null);

        var experimentSearchCriteria = ExperimentSearchCriteria.builder()
                .datasetId(datasetId)
                .name(name)
                .entityType(EntityType.TRACE)
                .datasetDeleted(datasetDeleted)
                .promptId(promptId)
                .sortingFields(sortingFields)
                .optimizationId(optimizationId)
                .types(types)
                .build();

        log.info("Finding experiments by '{}', page '{}', size '{}'", experimentSearchCriteria, page, size);
        var experiments = experimentService.find(page, size, experimentSearchCriteria)
                .map(experimentPage -> {
                    if (!metadata.canUseDynamicSorting()) {
                        return experimentPage.toBuilder().sortableBy(List.of()).build();
                    }
                    return experimentPage;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found experiments by '{}', count '{}', page '{}', size '{}'",
                experimentSearchCriteria, experiments.size(), page, size);
        return Response.ok().entity(experiments).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getExperimentById", summary = "Get experiment by id", description = "Get experiment by id", responses = {
            @ApiResponse(responseCode = "200", description = "Experiment resource", content = @Content(schema = @Schema(implementation = Experiment.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(Experiment.View.Public.class)
    public Response get(@PathParam("id") UUID id) {

        log.info("Getting experiment by id '{}'", id);
        var experiment = experimentService.getById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Got experiment by id '{}', datasetId '{}'", experiment.id(), experiment.datasetId());
        return Response.ok().entity(experiment).build();
    }

    @POST
    @Operation(operationId = "createExperiment", summary = "Create experiment", description = "Create experiment", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/experiments/{id}", schema = @Schema(implementation = String.class))})})
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Experiment.class))) @JsonView(Experiment.View.Write.class) @NotNull @Valid Experiment experiment,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating experiment with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                experiment.id(), experiment.name(), experiment.datasetName(), workspaceId);
        var id = experimentService.create(experiment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("Created experiment with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                id, experiment.name(), experiment.datasetName(), workspaceId);
        return Response.created(uri).build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteExperimentsById", summary = "Delete experiments by id", description = "Delete experiments by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")})
    public Response deleteExperimentsById(
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteIdsHolder.class))) @NotNull @Valid DeleteIdsHolder request) {

        log.info("Deleting experiments, count '{}'", request.ids());
        experimentService.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted experiments, count '{}'", request.ids());
        return Response.noContent().build();
    }

    @POST
    @Path("/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "streamExperiments", summary = "Stream experiments", description = "Stream experiments", responses = {
            @ApiResponse(responseCode = "200", description = "Experiments stream or error during process", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    Experiment.class,
                    ErrorMessage.class
            }), maxItems = 2000)))
    })
    @JsonView(Experiment.View.Public.class)
    public ChunkedOutput<JsonNode> streamExperiments(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentStreamRequest.class))) @NotNull @Valid ExperimentStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();
        log.info("Streaming experiments by '{}', workspaceId '{}', userName '{}'", request, workspaceId, userName);
        var experiments = experimentService.get(request)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));
        var stream = streamer.getOutputStream(experiments);
        log.info("Streamed experiments by '{}', workspaceId '{}', userName '{}'", request, workspaceId, userName);
        return stream;
    }

    // Experiment Item Resources

    @GET
    @Path("/items/{id}")
    @Operation(operationId = "getExperimentItemById", summary = "Get experiment item by id", description = "Get experiment item by id", responses = {
            @ApiResponse(responseCode = "200", description = "Experiment item resource", content = @Content(schema = @Schema(implementation = ExperimentItem.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(ExperimentItem.View.Public.class)
    public Response getExperimentItem(@PathParam("id") UUID id) {

        log.info("Getting experiment item by id '{}'", id);
        var experimentItem = experimentItemService.get(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got experiment item by id '{}', experimentId '{}', datasetItemId '{}', traceId '{}'",
                experimentItem.id(),
                experimentItem.experimentId(),
                experimentItem.datasetItemId(),
                experimentItem.traceId());
        return Response.ok().entity(experimentItem).build();
    }

    @POST
    @Path("/items/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "streamExperimentItems", summary = "Stream experiment items", description = "Stream experiment items", responses = {
            @ApiResponse(responseCode = "200", description = "Experiment items stream or error during process", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    ExperimentItem.class,
                    ErrorMessage.class
            }), maxItems = 2000)))
    })
    public ChunkedOutput<JsonNode> streamExperimentItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemStreamRequest.class))) @NotNull @Valid ExperimentItemStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();
        log.info("Streaming experiment items by '{}', workspaceId '{}'", request, workspaceId);
        var criteria = ExperimentItemSearchCriteria.builder()
                .experimentName(request.experimentName())
                .limit(request.limit())
                .lastRetrievedId(request.lastRetrievedId())
                .truncate(request.truncate())
                .build();
        var items = experimentItemService.getExperimentItems(criteria)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));
        var stream = streamer.getOutputStream(items);
        log.info("Streamed experiment items by '{}', workspaceId '{}'", request, workspaceId);
        return stream;
    }

    @POST
    @Path("/items")
    @Operation(operationId = "createExperimentItems", summary = "Create experiment items", description = "Create experiment items", responses = {
            @ApiResponse(responseCode = "204", description = "No content")})
    @RateLimited
    @UsageLimited
    public Response createExperimentItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemsBatch.class))) @NotNull @Valid ExperimentItemsBatch request) {

        // Generate ids for items without ids before the retryable operation
        Set<ExperimentItem> newRequest = request.experimentItems()
                .stream()
                .map(item -> {
                    if (item.id() == null) {
                        return item.toBuilder().id(idGenerator.generateId()).build();
                    }
                    return item;
                }).collect(Collectors.toSet());

        log.info("Creating experiment items, count '{}'", newRequest.size());
        experimentItemService.create(newRequest)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();
        log.info("Created experiment items, count '{}'", newRequest.size());
        return Response.noContent().build();
    }

    @POST
    @Path("/items/delete")
    @Operation(operationId = "deleteExperimentItems", summary = "Delete experiment items", description = "Delete experiment items", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteExperimentItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemsDelete.class))) @NotNull @Valid ExperimentItemsDelete request) {

        log.info("Deleting experiment items, count '{}'", request.ids().size());
        experimentItemService.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted experiment items, count '{}'", request.ids().size());
        return Response.noContent().build();
    }

    @PUT
    @Path("/items/bulk")
    @Operation(operationId = "experimentItemsBulk", summary = "Record experiment items in bulk", description = "Record experiment items in bulk with traces, spans, and feedback scores. "
            +
            "Maximum request size is 4MB.", responses = {
                    @ApiResponse(responseCode = "204", description = "No content"),
                    @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = com.comet.opik.api.error.ErrorMessage.class))),
            })
    @RateLimited
    @UsageLimited
    public Response experimentItemsBulk(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemBulkUpload.class))) @JsonView({
                    ExperimentItemBulkUpload.View.Write.class}) @NotNull @Valid ExperimentItemBulkUpload request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Recording experiment items in bulk, count '{}'", request.items().size());

        List<ExperimentItemBulkRecord> items = request.items()
                .stream()
                .map(item -> ExperimentItemBulkMapper.addIdsIfRequired(idGenerator, item))
                .map(item -> {
                    ExperimentItemBulkValidator.validate(item);
                    return item;
                })
                .toList();

        Experiment experiment = Experiment.builder()
                .datasetName(request.datasetName())
                .name(request.experimentName())
                .build();

        experimentItemBulkIngestionService.ingest(experiment, items)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Recorded experiment items in bulk, count '{}'", request.items().size());

        return Response.noContent().build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "Find Feedback Score names", description = "Find Feedback Score names", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback Scores resource", content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(@QueryParam("experiment_ids") String experimentIdsQueryParam) {

        var experimentIds = Optional.ofNullable(experimentIdsQueryParam)
                .map(ParamsValidator::getIds)
                .orElse(Collections.emptySet());

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find feedback score names by experiment_ids '{}', on workspaceId '{}'",
                experimentIds, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getExperimentsFeedbackScoreNames(experimentIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found feedback score names '{}' by experiment_ids '{}', on workspaceId '{}'",
                feedbackScoreNames.scores().size(), experimentIds, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }
}
