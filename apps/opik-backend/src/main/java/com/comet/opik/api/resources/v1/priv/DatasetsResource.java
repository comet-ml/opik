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
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetUpdate;
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
import com.comet.opik.domain.DatasetCriteria;
import com.comet.opik.domain.DatasetExpansionService;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.Streamer;
import com.comet.opik.domain.workspaces.WorkspaceMetadataService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.JsonUtils;
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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.glassfish.jersey.server.ChunkedOutput;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
    private final @NonNull DatasetExpansionService expansionService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Streamer streamer;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;

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
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @Schema(description = "Truncate image included in either input, output or metadata") boolean truncate) {

        var queryFilters = filtersFactory.newFilters(filters, DatasetItemFilter.LIST_TYPE_REFERENCE);

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(id)
                .experimentIds(Set.of()) // Empty set for regular dataset items
                .filters(queryFilters)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding dataset items by id '{}', page '{}', size '{}', filters '{}' on workspace_id '{}'", id, page,
                size, filters, workspaceId);

        DatasetItem.DatasetItemPage datasetItemPage = itemService.getItems(page, size, datasetItemSearchCriteria)
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

        log.info("Creating dataset items batch by datasetId '{}', datasetName '{}', size '{}' on workspaceId '{}'",
                batch.datasetId(), batch.datasetId(), batch.items().size(), workspaceId);
        itemService.save(new DatasetItemBatch(batch.datasetName(), batch.datasetId(), items))
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info("Created dataset items batch by datasetId '{}', datasetName '{}', size '{}' on workspaceId '{}'",
                batch.datasetId(), batch.datasetId(), batch.items().size(), workspaceId);

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
    })
    @RateLimited
    public Response createDatasetItemsFromCsv(
            @FormDataParam("file") @NotNull InputStream fileInputStream,
            @FormDataParam("dataset_id") @NotNull UUID datasetId,
            @FormDataParam("workspace_name") String workspaceName) throws IOException {

        final String contextWorkspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Handle default workspace: ensure we use the UUID, not "admin"
        final String workspaceId;
        if (ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(workspaceName) ||
                ProjectService.DEFAULT_USER.equalsIgnoreCase(contextWorkspaceId)) {
            workspaceId = ProjectService.DEFAULT_WORKSPACE_ID;
            log.info("Using default workspace UUID: '{}'", workspaceId);
        } else {
            workspaceId = contextWorkspaceId;
        }

        // Validate that workspaceId is a UUID, not a workspace name
        // If it's not a valid UUID format, it means the context has the wrong value
        if (!isValidUUID(workspaceId)) {
            log.error("Invalid workspaceId format: '{}'. Expected UUID but got non-UUID value. " +
                    "workspace_name parameter: '{}', userName: '{}'", workspaceId, workspaceName, userName);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage("Invalid workspace ID in request context. Please contact support."))
                    .build();
        }

        log.info("Starting CSV upload for dataset '{}' on workspaceId '{}', workspaceName '{}', userName '{}'",
                datasetId, workspaceId, workspaceName, userName);

        // Read the entire stream into memory for async processing
        // For very large files, consider streaming or temporary file storage
        byte[] fileBytes = fileInputStream.readAllBytes();

        // Validate CSV headers quickly before returning
        try (InputStreamReader reader = new InputStreamReader(
                new java.io.ByteArrayInputStream(fileBytes), StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .setIgnoreEmptyLines(true)
                        .build()
                        .parse(reader)) {

            List<String> headers = parser.getHeaderNames();
            if (headers.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage("CSV file must contain headers"))
                        .build();
            }

            // Check if there's at least one data row
            boolean hasData = parser.iterator().hasNext();
            if (!hasData) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage("CSV file contains no data rows"))
                        .build();
            }

            log.info("CSV validation passed for dataset '{}' on workspaceId '{}', file size: '{}' bytes, headers: '{}', starting async processing",
                    datasetId, workspaceId, fileBytes.length, headers);

            // Verify dataset exists before starting async processing
            try {
                Dataset dataset = service.findById(datasetId, workspaceId, requestContext.get().getVisibility());
                log.info("Dataset verified before async processing, datasetId: '{}', workspaceId: '{}', dataset name: '{}'",
                        datasetId, workspaceId, dataset.name());
            } catch (Exception e) {
                log.error("Dataset not found before starting async processing, datasetId: '{}', workspaceId: '{}', error: '{}'",
                        datasetId, workspaceId, e.getMessage(), e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage("Dataset not found. Please ensure the dataset was created successfully."))
                        .build();
            }

            // Capture RequestContext for async processing
            RequestContext capturedContext = requestContext.get();

            // Process CSV asynchronously in batches
            // Add a small delay to ensure dataset creation transaction has committed
            log.info("Scheduling async CSV processing for dataset '{}' on workspaceId '{}'", datasetId, workspaceId);
            Mono.delay(Duration.ofMillis(500))
                    .then(processCsvInBatches(fileBytes, datasetId, workspaceId, userName, headers, capturedContext))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSubscribe(subscription -> log.info("Async CSV processing started for dataset '{}' on workspaceId '{}'",
                            datasetId, workspaceId))
                    .doOnNext(totalItems -> log.info("Completed CSV processing for dataset '{}' on workspaceId '{}', total items: '{}'",
                            datasetId, workspaceId, totalItems))
                    .doOnError(error -> log.error("Error processing CSV file for dataset '{}' on workspaceId '{}', error: '{}'",
                            datasetId, workspaceId, error.getMessage(), error))
                    .subscribe(
                            totalItems -> log.info("CSV processing subscription completed for dataset '{}' on workspaceId '{}', total items: '{}'",
                                    datasetId, workspaceId, totalItems),
                            error -> {
                                log.error("CSV processing failed for dataset '{}' on workspaceId '{}', error: '{}'",
                                        datasetId, workspaceId, error.getMessage(), error);
                                // Log full stack trace
                                log.error("Full stack trace for CSV processing error", error);
                            }
                    );

            // Return immediately - processing happens in background
            return Response.status(Response.Status.ACCEPTED)
                    .entity(new ErrorMessage("CSV upload accepted, processing in background"))
                    .build();
        } catch (Exception e) {
            log.error("Error validating CSV file for dataset '{}' on workspaceId '{}'", datasetId, workspaceId, e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage("Failed to validate CSV file: " + e.getMessage()))
                    .build();
        }
    }

    private Mono<Long> processCsvInBatches(byte[] fileBytes, UUID datasetId, String workspaceId,
                                          String userName, List<String> headers, RequestContext context) {
        log.info("Starting CSV batch processing for dataset '{}' on workspaceId '{}', file size: '{}' bytes",
                datasetId, workspaceId, fileBytes.length);

        return Mono.fromCallable(() -> {
            log.info("Inside Mono.fromCallable for CSV processing, dataset '{}' on workspaceId '{}'", datasetId, workspaceId);

            try (InputStreamReader reader = new InputStreamReader(
                    new java.io.ByteArrayInputStream(fileBytes), StandardCharsets.UTF_8);
                    CSVParser parser = CSVFormat.DEFAULT
                            .builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setIgnoreHeaderCase(true)
                            .setTrim(true)
                            .setIgnoreEmptyLines(true)
                            .build()
                            .parse(reader)) {

                log.info("CSV parser created for dataset '{}' on workspaceId '{}', starting to iterate records",
                        datasetId, workspaceId);

                final int BATCH_SIZE = 1000;
                List<DatasetItem> batch = new ArrayList<>(BATCH_SIZE);
                long totalProcessed = 0;
                int batchNumber = 0;
                long recordCount = 0;

                for (CSVRecord record : parser) {
                    recordCount++;

                    if (recordCount % 10000 == 0) {
                        log.info("Processing record '{}' for dataset '{}' on workspaceId '{}'",
                                recordCount, datasetId, workspaceId);
                    }

                    Map<String, JsonNode> dataMap = headers.stream()
                            .collect(Collectors.toMap(
                                    header -> header,
                                    header -> {
                                        String value = record.get(header);
                                        return value != null && !value.isEmpty()
                                                ? JsonUtils.valueToTree(value)
                                                : JsonUtils.valueToTree("");
                                    }));

                    DatasetItem item = DatasetItem.builder()
                            .id(idGenerator.generateId())
                            .source(DatasetItemSource.MANUAL)
                            .data(dataMap)
                            .build();

                    batch.add(item);

                    // Save batch when it reaches BATCH_SIZE
                    if (batch.size() >= BATCH_SIZE) {
                        batchNumber++;
                        log.info("Batch '{}' ready to save for dataset '{}' on workspaceId '{}', batch size: '{}', total records processed: '{}'",
                                batchNumber, datasetId, workspaceId, batch.size(), recordCount);
                        totalProcessed += saveBatchSync(batch, datasetId, workspaceId, userName, batchNumber, context);
                        batch.clear();
                        log.info("Batch '{}' saved successfully for dataset '{}' on workspaceId '{}', continuing with next batch",
                                batchNumber, datasetId, workspaceId);
                    }
                }

                log.info("Finished iterating CSV records for dataset '{}' on workspaceId '{}', total records: '{}', batches saved: '{}', remaining batch size: '{}'",
                        datasetId, workspaceId, recordCount, batchNumber, batch.size());

                // Save remaining items
                if (!batch.isEmpty()) {
                    batchNumber++;
                    log.info("Saving final batch '{}' for dataset '{}' on workspaceId '{}', batch size: '{}'",
                            batchNumber, datasetId, workspaceId, batch.size());
                    totalProcessed += saveBatchSync(batch, datasetId, workspaceId, userName, batchNumber, context);
                    log.info("Final batch '{}' saved successfully for dataset '{}' on workspaceId '{}'",
                            batchNumber, datasetId, workspaceId);
                }

                log.info("Finished processing CSV for dataset '{}' on workspaceId '{}', total batches: '{}', total items: '{}', total records: '{}'",
                        datasetId, workspaceId, batchNumber, totalProcessed, recordCount);
                return totalProcessed;
            } catch (Exception e) {
                log.error("Exception in processCsvInBatches for dataset '{}' on workspaceId '{}'",
                        datasetId, workspaceId, e);
                throw e;
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(error -> log.error("Error in Mono.fromCallable for CSV processing, dataset '{}' on workspaceId '{}'",
                datasetId, workspaceId, error))
        .flatMap(total -> {
            log.info("Mono.flatMap called for CSV processing, dataset '{}' on workspaceId '{}', total: '{}'",
                    datasetId, workspaceId, total);
            return Mono.just(total);
        });
    }

    private long saveBatchSync(List<DatasetItem> items, UUID datasetId, String workspaceId,
                              String userName, int batchNumber, RequestContext context) {
        log.info("Starting to save batch '{}' for dataset '{}' on workspaceId '{}', items count: '{}'",
                batchNumber, datasetId, workspaceId, items.size());

        try {
            DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, items);
            log.info("Created DatasetItemBatch for batch '{}', dataset '{}', calling itemService.save()",
                    batchNumber, datasetId);

            itemService.save(batch)
                    .contextWrite(ctx -> {
                        log.debug("Setting request context for batch '{}', dataset '{}', workspaceId: '{}', userName: '{}'",
                                batchNumber, datasetId, workspaceId, userName);
                        return setRequestContext(ctx, workspaceId, userName, context.getVisibility());
                    })
                    .retryWhen(RetryUtils.handleConnectionError())
                    .doOnSubscribe(sub -> log.info("Subscribed to save operation for batch '{}', dataset '{}'",
                            batchNumber, datasetId))
                    .doOnSuccess(unused -> log.info("Save operation succeeded for batch '{}', dataset '{}'",
                            batchNumber, datasetId))
                    .doOnError(error -> log.error("Save operation failed for batch '{}', dataset '{}', error: '{}'",
                            batchNumber, datasetId, error.getMessage(), error))
                    .block();

            log.info("Successfully saved batch '{}' for dataset '{}' on workspaceId '{}', items: '{}'",
                    batchNumber, datasetId, workspaceId, items.size());
            return items.size();
        } catch (Exception e) {
            log.error("Exception saving batch '{}' for dataset '{}' on workspaceId '{}', error: '{}'",
                    batchNumber, datasetId, workspaceId, e.getMessage(), e);
            log.error("Full stack trace for batch save error", e);
            throw new RuntimeException("Failed to save batch " + batchNumber + " for dataset " + datasetId, e);
        }
    }

    /**
     * Validates if a string is a valid UUID format.
     * UUIDs have the format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (36 characters with dashes)
     */
    private boolean isValidUUID(String str) {
        if (str == null || str.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
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

        var metadata = workspaceMetadataService
                .getWorkspaceMetadata(requestContext.get().getWorkspaceId())
                // Context not used for workspace metadata but added for consistency with project metadata endpoints.
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        if (!sortingFields.isEmpty() && metadata.cannotUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(datasetId)
                .experimentIds(experimentIds)
                .filters(queryFilters)
                .sortingFields(sortingFields)
                .search(search)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dataset items with experiment items by '{}', page '{}', size '{}' on workspaceId '{}'",
                datasetItemSearchCriteria, page, size, workspaceId);

        var datasetItemPage = itemService.getItems(page, size, datasetItemSearchCriteria)
                .map(it -> {
                    // Remove sortableBy fields if dynamic sorting is disabled due to workspace size
                    if (metadata.cannotUseDynamicSorting()) {
                        return it.toBuilder().sortableBy(List.of()).build();
                    }
                    return it;
                })
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
}
