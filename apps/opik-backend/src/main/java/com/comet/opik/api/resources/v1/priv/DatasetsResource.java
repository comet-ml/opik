package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetCriteria;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSearchCriteria;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.Streamer;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.glassfish.jersey.server.ChunkedOutput;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/datasets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Datasets", description = "Dataset resources")
public class DatasetsResource {

    private final @NonNull DatasetService service;
    private final @NonNull DatasetItemService itemService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Streamer streamer;
    private final @NonNull SortingFactoryDatasets sortingFactory;

    @GET
    @Path("/{id}")
    @Operation(operationId = "getDatasetById", summary = "Get dataset by id", description = "Get dataset by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset resource", content = @Content(schema = @Schema(implementation = Dataset.class)))
    })
    @JsonView(Dataset.View.Public.class)
    public Response getDatasetById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dataset by id '{}' on workspaceId '{}'", id, workspaceId);
        Dataset dataset = service.findById(id);
        log.info("Found dataset by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(dataset).build();
    }

    @GET
    @Operation(operationId = "findDatasets", summary = "Find datasets", description = "Find datasets", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset resource", content = @Content(schema = @Schema(implementation = DatasetPage.class)))
    })
    @JsonView(Dataset.View.Public.class)
    public Response findDatasets(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("with_experiments_only") boolean withExperimentsOnly,
            @QueryParam("with_optimizations_only") boolean withOptimizationsOnly,
            @QueryParam("prompt_id") UUID promptId,
            @QueryParam("name") String name,
            @QueryParam("sorting") String sorting) {

        var criteria = DatasetCriteria.builder()
                .name(name)
                .withExperimentsOnly(withExperimentsOnly)
                .promptId(promptId)
                .withOptimizationsOnly(withOptimizationsOnly)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding datasets by '{}', sorted with: {}, on workspaceId '{}'", criteria, sorting, workspaceId);
        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        DatasetPage datasetPage = service.find(page, size, criteria, sortingFields);
        log.info("Found datasets by '{}', sorted with: {}, count '{}' on workspaceId '{}'", criteria, sorting,
                datasetPage.size(), workspaceId);

        return Response.ok(datasetPage).build();
    }

    @POST
    @Operation(operationId = "createDataset", summary = "Create dataset", description = "Create dataset", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/api/v1/private/datasets/{id}", schema = @Schema(implementation = String.class))
            })
    })
    @RateLimited
    public Response createDataset(
            @RequestBody(content = @Content(schema = @Schema(implementation = Dataset.class))) @JsonView(Dataset.View.Write.class) @NotNull @Valid Dataset dataset,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dataset with name '{}', on workspace_id '{}'", dataset.name(), workspaceId);
        Dataset savedDataset = service.save(dataset);
        log.info("Created dataset with name '{}', id '{}', on workspace_id '{}'", savedDataset.name(),
                savedDataset.id(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedDataset.id().toString())).build();
        return Response.created(uri).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateDataset", summary = "Update dataset by id", description = "Update dataset by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    @RateLimited
    public Response updateDataset(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetUpdate.class))) @NotNull @Valid DatasetUpdate datasetUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating dataset by id '{}' on workspace_id '{}'", id, workspaceId);
        service.update(id, datasetUpdate);
        log.info("Updated dataset by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteDataset", summary = "Delete dataset by id", description = "Delete dataset by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteDataset(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting dataset by id '{}' on workspace_id '{}'", id, workspaceId);
        service.delete(id);
        log.info("Deleted dataset by id '{}' on workspace_id '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteDatasetByName", summary = "Delete dataset by name", description = "Delete dataset by name", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteDatasetByName(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetIdentifier.class))) @NotNull @Valid DatasetIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dataset by name '{}' on workspace_id '{}'", identifier.datasetName(), workspaceId);
        service.delete(identifier);
        log.info("Deleted dataset by name '{}' on workspace_id '{}'", identifier.datasetName(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete-batch")
    @Operation(operationId = "deleteDatasetsBatch", summary = "Delete datasets", description = "Delete datasets batch", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteDatasetsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting datasets by ids, count '{}' on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted datasets by ids, count '{}' on workspace_id '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/retrieve")
    @Operation(operationId = "getDatasetByIdentifier", summary = "Get dataset by name", description = "Get dataset by name", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset resource", content = @Content(schema = @Schema(implementation = Dataset.class))),
    })
    @JsonView(Dataset.View.Public.class)
    public Response getDatasetByIdentifier(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetIdentifier.class))) @NotNull @Valid DatasetIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();
        String name = identifier.datasetName();

        log.info("Finding dataset by name '{}' on workspace_id '{}'", name, workspaceId);
        Dataset dataset = service.findByName(workspaceId, name, visibility);
        log.info("Found dataset by name '{}', id '{}' on workspace_id '{}'", name, dataset.id(), workspaceId);

        return Response.ok(dataset).build();
    }

    // Dataset Item Resources

    @GET
    @Path("/items/{itemId}")
    @Operation(operationId = "getDatasetItemById", summary = "Get dataset item by id", description = "Get dataset item by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset item resource", content = @Content(schema = @Schema(implementation = DatasetItem.class)))
    })
    @JsonView(DatasetItem.View.Public.class)
    public Response getDatasetItemById(@PathParam("itemId") @NotNull UUID itemId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dataset item by id '{}' on workspace_id '{}'", itemId, workspaceId);
        DatasetItem datasetItem = itemService.get(itemId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found dataset item by id '{}' on workspace_id '{}'", itemId, workspaceId);

        return Response.ok(datasetItem).build();
    }

    @GET
    @Path("/{id}/items")
    @Operation(operationId = "getDatasetItems", summary = "Get dataset items", description = "Get dataset items", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset items resource", content = @Content(schema = @Schema(implementation = DatasetItem.DatasetItemPage.class)))
    })
    @JsonView(DatasetItem.View.Public.class)
    public Response getDatasetItems(
            @PathParam("id") UUID id,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding dataset items by id '{}', page '{}', size '{} on workspace_id '{}''", id, page, size,
                workspaceId);
        DatasetItem.DatasetItemPage datasetItemPage = itemService.getItems(id, page, size, truncate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found dataset items by id '{}', count '{}', page '{}', size '{} on workspace_id '{}''", id,
                datasetItemPage.content().size(), page, size, workspaceId);

        return Response.ok(datasetItemPage).build();
    }

    @POST
    @Path("/items/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "streamDatasetItems", summary = "Stream dataset items", description = "Stream dataset items", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset items stream or error during process", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    DatasetItem.class,
                    ErrorMessage.class
            }), maxItems = 2000)))
    })
    public ChunkedOutput<JsonNode> streamDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemStreamRequest.class))) @NotNull @Valid DatasetItemStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();
        var visibility = requestContext.get().getVisibility();

        log.info("Streaming dataset items by '{}' on workspaceId '{}'", request, workspaceId);
        var items = itemService.getItems(workspaceId, request, visibility)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));
        var outputStream = streamer.getOutputStream(items);
        log.info("Streamed dataset items by '{}' on workspaceId '{}'", request, workspaceId);
        return outputStream;
    }

    @PUT
    @Path("/items")
    @Operation(operationId = "createOrUpdateDatasetItems", summary = "Create/update dataset items", description = "Create/update dataset items based on dataset item id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    @RateLimited
    public Response createDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemBatch.class))) @JsonView({
                    DatasetItem.View.Write.class}) @NotNull @Valid DatasetItemBatch batch) {

        // Generate ids for items without ids before the retryable operation
        List<DatasetItem> items = batch.items().stream().map(item -> {
            if (item.id() == null) {
                return item.toBuilder().id(idGenerator.generateId()).build();
            }
            return item;
        }).toList();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dataset items batch by datasetId '{}', datasetName '{}', size '{}' on workspaceId '{}'",
                batch.datasetId(), batch.datasetId(), batch.items().size(), workspaceId);
        itemService.save(new DatasetItemBatch(batch.datasetName(), batch.datasetId(), items))
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();
        log.info("Created dataset items batch by datasetId '{}', datasetName '{}', size '{}' on workspaceId '{}'",
                batch.datasetId(), batch.datasetId(), batch.items().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/items/delete")
    @Operation(operationId = "deleteDatasetItems", summary = "Delete dataset items", description = "Delete dataset items", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemsDelete.class))) @NotNull @Valid DatasetItemsDelete request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dataset items by size'{}' on workspaceId '{}'", request, workspaceId);
        itemService.delete(request.itemIds())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted dataset items by size'{}' on workspaceId '{}'", request, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/items/experiments/items")
    @Operation(operationId = "findDatasetItemsWithExperimentItems", summary = "Find dataset items with experiment items", description = "Find dataset items with experiment items", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset item resource", content = @Content(schema = @Schema(implementation = DatasetItem.DatasetItemPage.class)))
    })
    @JsonView(ExperimentItem.View.Compare.class)
    public Response findDatasetItemsWithExperimentItems(
            @PathParam("id") UUID datasetId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("experiment_ids") @NotNull @NotBlank String experimentIdsQueryParam,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate) {

        var experimentIds = ParamsValidator.getIds(experimentIdsQueryParam);

        var queryFilters = filtersFactory.newFilters(filters, ExperimentsComparisonFilter.LIST_TYPE_REFERENCE);

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(datasetId)
                .experimentIds(experimentIds)
                .filters(queryFilters)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dataset items with experiment items by '{}', page '{}', size '{}' on workspaceId '{}'",
                datasetItemSearchCriteria, page, size, workspaceId);

        var datasetItemPage = itemService.getItems(page, size, datasetItemSearchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info(
                "Found dataset items with experiment items by '{}', count '{}', page '{}', size '{}' on workspaceId '{}'",
                datasetItemSearchCriteria, datasetItemPage.content().size(), page, size, workspaceId);
        return Response.ok(datasetItemPage).build();
    }

    @GET
    @Path("/{id}/items/experiments/items/output/columns")
    @Operation(operationId = "getDatasetItemsOutputColumns", summary = "Get dataset items output columns", description = "Get dataset items output columns", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset item output columns", content = @Content(schema = @Schema(implementation = PageColumns.class)))
    })
    public Response getDatasetItemsOutputColumns(
            @PathParam("id") @NotNull UUID datasetId,
            @QueryParam("experiment_ids") String experimentIdsQueryParam) {

        var experimentIds = Optional.ofNullable(experimentIdsQueryParam)
                .filter(Predicate.not(String::isEmpty))
                .map(ParamsValidator::getIds)
                .orElse(null);

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding traces output columns by datasetId '{}', experimentIds '{}', on workspaceId '{}'",
                datasetId, experimentIds, workspaceId);

        PageColumns columns = itemService.getOutputColumns(datasetId, experimentIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found traces output columns by datasetId '{}', experimentIds '{}', on workspaceId '{}'",
                datasetId, experimentIds, workspaceId);

        return Response.ok(columns).build();
    }

}
