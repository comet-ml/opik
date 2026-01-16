package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreateDatasetItemsFromSpansRequest;
import com.comet.opik.api.CreateDatasetItemsFromTracesRequest;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetExpansion;
import com.comet.opik.api.DatasetExpansionResponse;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.filter.DatasetFilter;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.CsvDatasetExportService;
import com.comet.opik.domain.CsvDatasetItemProcessor;
import com.comet.opik.domain.DatasetCriteria;
import com.comet.opik.domain.DatasetExpansionService;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.Streamer;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.RetryUtils;
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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
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
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.glassfish.jersey.server.ChunkedOutput;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static org.apache.commons.collections4.CollectionUtils.emptyIfNull;

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
    private final @NonNull DatasetExpansionService expansionService;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Streamer streamer;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull CsvDatasetItemProcessor csvProcessor;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull CsvDatasetExportService csvExportService;

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
            @QueryParam("name") @Schema(description = "Filter datasets by name (partial match, case insensitive)") String name,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        var queryFilters = filtersFactory.newFilters(filters, DatasetFilter.LIST_TYPE_REFERENCE);

        var criteria = DatasetCriteria.builder()
                .name(name)
                .withExperimentsOnly(withExperimentsOnly)
                .promptId(promptId)
                .withOptimizationsOnly(withOptimizationsOnly)
                .filters(queryFilters)
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

    @POST
    @Path("/{id}/expansions")
    @Operation(operationId = "expandDataset", summary = "Expand dataset with synthetic samples", description = "Generate synthetic dataset samples using LLM based on existing data patterns", responses = {
            @ApiResponse(responseCode = "200", description = "Generated synthetic samples", content = @Content(schema = @Schema(implementation = DatasetExpansionResponse.class)))
    })
    @RateLimited
    public Response expandDataset(
            @PathParam("id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetExpansion.class))) @JsonView(DatasetExpansion.View.Write.class) @NotNull @Valid DatasetExpansion request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Expanding dataset with id '{}' on workspaceId '{}'", datasetId, workspaceId);
        var response = expansionService.expandDataset(datasetId, request);
        log.info("Expanded dataset with id '{}' on workspaceId '{}', total samples '{}'",
                datasetId, workspaceId, response.totalGenerated());
        return Response.ok(response).build();
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

    @PATCH
    @Path("/items/batch")
    @Operation(operationId = "batchUpdateDatasetItems", summary = "Batch update dataset items", description = "Update multiple dataset items", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response batchUpdate(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemBatchUpdate.class))) @Valid @NotNull DatasetItemBatchUpdate batchUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Batch updating dataset items. workspaceId='{}', idsSize='{}', filters='{}'", workspaceId,
                emptyIfNull(batchUpdate.ids()).size(), emptyIfNull(batchUpdate.filters()).size());

        itemService.batchUpdate(batchUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Batch updated dataset items. workspaceId='{}', idsSize='{}', filters='{}'", workspaceId,
                emptyIfNull(batchUpdate.ids()).size(), emptyIfNull(batchUpdate.filters()).size());

        return Response.noContent().build();
    }

    @PATCH
    @Path("/items/{itemId}")
    @Operation(operationId = "patchDatasetItem", summary = "Partially update dataset item by id", description = "Partially update dataset item by id. Only provided fields will be updated.", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "Dataset item not found")
    })
    @RateLimited
    public Response patchDatasetItem(
            @PathParam("itemId") @NotNull UUID itemId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItem.class))) @JsonView(DatasetItem.View.Write.class) @NotNull DatasetItem item) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Patching dataset item by id '{}' on workspace_id '{}'", itemId, workspaceId);
        itemService.patch(itemId, item)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info("Patched dataset item by id '{}' on workspace_id '{}'", itemId, workspaceId);

        return Response.noContent().build();
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
            @QueryParam("version") @Schema(description = "Version hash or tag to fetch specific dataset version") String version,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate) {

        var queryFilters = filtersFactory.newFilters(filters, DatasetItemFilter.LIST_TYPE_REFERENCE);
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info(
                "Finding dataset items by id '{}', version '{}', page '{}', size '{}', filters '{}' on workspace_id '{}'",
                id, version, page, size, filters, workspaceId);

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(id)
                .experimentIds(Set.of()) // Empty set for regular dataset items
                .filters(queryFilters)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .versionHashOrTag(version)
                .build();

        var datasetItemPage = itemService.getItems(page, size, datasetItemSearchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found dataset items by id '{}', count '{}', page '{}', size '{}' on workspace_id '{}'", id,
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

        log.info(
                "Creating dataset items batch by datasetId '{}', datasetName '{}', size '{}', batchGroupId '{}' on workspaceId '{}'",
                batch.datasetId(), batch.datasetName(), batch.items().size(), batch.batchGroupId(), workspaceId);

        DatasetItemBatch batchWithIds = batch.toBuilder()
                .items(items)
                .build();

        itemService.save(batchWithIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info(
                "Saved dataset items batch by datasetId '{}', datasetName '{}', size '{}', batchGroupId '{}' on workspaceId '{}'",
                batch.datasetId(), batch.datasetName(), batch.items().size(), batch.batchGroupId(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{dataset_id}/items/from-traces")
    @Operation(operationId = "createDatasetItemsFromTraces", summary = "Create dataset items from traces", description = "Create dataset items from traces with enriched metadata", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    @RateLimited
    public Response createDatasetItemsFromTraces(
            @PathParam("dataset_id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDatasetItemsFromTracesRequest.class))) @NotNull @Valid CreateDatasetItemsFromTracesRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dataset items from traces for dataset '{}', trace count '{}' on workspaceId '{}'",
                datasetId, request.traceIds().size(), workspaceId);

        itemService.createFromTraces(datasetId, request.traceIds(), request.enrichmentOptions())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("Created dataset items from traces for dataset '{}', trace count '{}' on workspaceId '{}'",
                datasetId, request.traceIds().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{dataset_id}/items/from-spans")
    @Operation(operationId = "createDatasetItemsFromSpans", summary = "Create dataset items from spans", description = "Create dataset items from spans with enriched metadata", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    @RateLimited
    public Response createDatasetItemsFromSpans(
            @PathParam("dataset_id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDatasetItemsFromSpansRequest.class))) @NotNull @Valid CreateDatasetItemsFromSpansRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dataset items from spans for dataset '{}', span count '{}' on workspaceId '{}'",
                datasetId, request.spanIds().size(), workspaceId);

        itemService.createFromSpans(datasetId, request.spanIds(), request.enrichmentOptions())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("Created dataset items from spans for dataset '{}', span count '{}' on workspaceId '{}'",
                datasetId, request.spanIds().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/items/from-csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(operationId = "createDatasetItemsFromCsv", summary = "Create dataset items from CSV file", description = "Create dataset items from uploaded CSV file. CSV should have headers in the first row. Processing happens asynchronously in batches.", responses = {
            @ApiResponse(responseCode = "202", description = "Accepted - CSV processing started"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - CSV upload feature is disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @RateLimited
    public Response createDatasetItemsFromCsv(
            @FormDataParam("file") @NotNull InputStream fileInputStream,
            @FormDataParam("dataset_id") @NotNull UUID datasetId) {

        if (!featureFlags.isCsvUploadEnabled()) {
            log.warn("CSV upload feature is disabled, returning 404");
            throw new NotFoundException("CSV upload feature is not enabled");
        }

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        Visibility visibility = requestContext.get().getVisibility();

        log.info("CSV upload request for dataset '{}' on workspaceId '{}'", datasetId, workspaceId);

        csvProcessor.processUploadedCsv(fileInputStream, datasetId, workspaceId, userName, visibility);

        log.info("CSV upload accepted for dataset '{}' on workspaceId '{}', processing asynchronously", datasetId,
                workspaceId);

        return Response.status(Response.Status.ACCEPTED).build();
    }

    @POST
    @Path("/{id}/items/changes")
    @Operation(operationId = "applyDatasetItemChanges", summary = "Apply changes to dataset items", description = """
            Apply delta changes (add, edit, delete) to a dataset version with conflict detection.

            This endpoint:
            - Creates a new version with the applied changes
            - Validates that baseVersion matches the latest version (unless override=true)
            - Returns 409 Conflict if baseVersion is stale and override is not set

            Use `override=true` query parameter to force version creation even with stale baseVersion.
            """, responses = {
            @ApiResponse(responseCode = "201", description = "Version created successfully", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Dataset or version not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Version conflict - baseVersion is not the latest", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response applyDatasetItemChanges(
            @PathParam("id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemChanges.class))) @NotNull @Valid DatasetItemChanges changes,
            @QueryParam("override") @DefaultValue("false") boolean override) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Applying dataset item changes for dataset '{}', baseVersion '{}', override '{}' on workspaceId '{}'",
                datasetId, changes.baseVersion(), override, workspaceId);

        DatasetVersion newVersion = itemService.applyDeltaChanges(datasetId, changes, override)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();

        log.info("Applied changes to dataset '{}', created version '{}' on workspaceId '{}'",
                datasetId, newVersion.versionHash(), workspaceId);

        // Build location header pointing to the newly created version
        String location = String.format("/v1/private/datasets/%s/versions/%s",
                datasetId, newVersion.id());

        return Response.status(Response.Status.CREATED)
                .entity(newVersion)
                .header("Location", location)
                .build();
    }

    @POST
    @Path("/items/delete")
    @Operation(operationId = "deleteDatasetItems", summary = "Delete dataset items", description = """
            Delete dataset items using one of two modes:
            1. **Delete by IDs**: Provide 'item_ids' to delete specific items by their IDs
            2. **Delete by filters**: Provide 'dataset_id' with optional 'filters' to delete items matching criteria

            When using filters, an empty 'filters' array will delete all items in the specified dataset.
            """, responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad request - invalid parameters or conflicting fields"),
    })
    public Response deleteDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemsDelete.class))) @NotNull @Valid DatasetItemsDelete request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info(
                "Deleting dataset items. workspaceId='{}', itemIdsSize='{}', datasetId='{}', filtersSize='{}', batchGroupId='{}'",
                workspaceId,
                emptyIfNull(request.itemIds()).size(),
                request.datasetId(),
                emptyIfNull(request.filters()).size(),
                request.batchGroupId());

        itemService.delete(request.itemIds(), request.datasetId(), request.filters(), request.batchGroupId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info(
                "Deleted dataset items. workspaceId='{}', itemIdsSize='{}', datasetId='{}', filtersSize='{}', batchGroupId='{}'",
                workspaceId,
                emptyIfNull(request.itemIds()).size(),
                request.datasetId(),
                emptyIfNull(request.filters()).size(),
                request.batchGroupId());

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
            @QueryParam("experiment_ids") @NotNull String experimentIdsQueryParam,
            @QueryParam("filters") String filters,
            @QueryParam("sorting") String sorting,
            @QueryParam("search") String search,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate) {

        var experimentIds = ParamsValidator.getIds(experimentIdsQueryParam);

        if (experimentIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                            "experiment_ids cannot be empty"))
                    .build();
        }

        var queryFilters = filtersFactory.newFilters(filters, ExperimentsComparisonFilter.LIST_TYPE_REFERENCE);

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(datasetId)
                .experimentIds(experimentIds)
                .filters(queryFilters)
                .sortingFields(sortingFields)
                .search(search)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .versionHashOrTag(null) // Service layer will resolve to experiment's version when experimentIds present
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

    @Timed
    @GET
    @Path("/{id}/items/experiments/items/stats")
    @Operation(operationId = "getDatasetExperimentItemsStats", summary = "Get experiment items stats for dataset", description = "Get experiment items stats for dataset", responses = {
            @ApiResponse(responseCode = "200", description = "Experiment items stats resource", content = @Content(schema = @Schema(implementation = com.comet.opik.api.ProjectStats.class)))
    })
    @JsonView({com.comet.opik.api.ProjectStats.ProjectStatItem.View.Public.class})
    @SuppressWarnings("unchecked")
    public Response getDatasetExperimentItemsStats(
            @PathParam("id") UUID datasetId,
            @QueryParam("experiment_ids") @NotNull String experimentIdsQueryParam,
            @QueryParam("filters") String filters) {

        var experimentIds = ParamsValidator.getIds(experimentIdsQueryParam);

        if (experimentIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                            "experiment_ids cannot be empty"))
                    .build();
        }

        List<ExperimentsComparisonFilter> queryFilters = (List<ExperimentsComparisonFilter>) filtersFactory
                .newFilters(filters, ExperimentsComparisonFilter.LIST_TYPE_REFERENCE);

        log.info("Getting experiment items stats for dataset '{}' and experiments '{}' with filters '{}'",
                datasetId, experimentIds, filters);
        var stats = itemService.getExperimentItemsStats(datasetId, experimentIds, queryFilters)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got experiment items stats for dataset '{}' and experiments '{}', count '{}'", datasetId,
                experimentIds, stats.stats().size());
        return Response.ok(stats).build();
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

    /**
     * Sub-resource locator for dataset version operations.
     * Delegates all requests under /{id}/versions to DatasetVersionsResource.
     *
     * @param datasetId the dataset ID from the path parameter
     * @return a new instance of DatasetVersionsResource configured for this dataset
     */
    @Path("/{id}/versions")
    public DatasetVersionsResource versions(@PathParam("id") UUID datasetId) {
        return new DatasetVersionsResource(datasetId, versionService, requestContext, featureFlags);
    }
}
