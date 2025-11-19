package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

@ImplementedBy(DatasetItemServiceImpl.class)
public interface DatasetItemService {

    Mono<Void> verifyDatasetExistsAndSave(DatasetItemBatch batch);

    Mono<Long> saveBatch(UUID datasetId, List<DatasetItem> items);

    Mono<Void> createFromTraces(UUID datasetId, Set<UUID> traceIds, TraceEnrichmentOptions enrichmentOptions);

    Mono<Void> createFromSpans(UUID datasetId, Set<UUID> spanIds,
            SpanEnrichmentOptions enrichmentOptions);

    Mono<DatasetItem> get(UUID id);

    Mono<Void> patch(UUID id, DatasetItem item);

    Mono<Void> batchUpdate(DatasetItemBatchUpdate batchUpdate);

    Mono<Void> delete(List<UUID> ids);

    Mono<DatasetItemPage> getItems(int page, int size, DatasetItemSearchCriteria datasetItemSearchCriteria);

    Flux<DatasetItem> getItems(String workspaceId, DatasetItemStreamRequest request, Visibility visibility);

    Mono<PageColumns> getOutputColumns(UUID datasetId, Set<UUID> experimentIds);

    Mono<ProjectStats> getExperimentItemsStats(UUID datasetId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemServiceImpl implements DatasetItemService {

    private final @NonNull DatasetItemDAO dao;
    private final @NonNull DatasetService datasetService;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull TraceEnrichmentService traceEnrichmentService;
    private final @NonNull SpanEnrichmentService spanEnrichmentService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull TransactionTemplate template;

    @Override
    @WithSpan
    public Mono<Void> verifyDatasetExistsAndSave(@NonNull DatasetItemBatch batch) {
        if (batch.datasetId() == null && batch.datasetName() == null) {
            return Mono.error(failWithError("dataset_id or dataset_name must be provided"));
        }

        return getDatasetId(batch)
                .flatMap(it -> saveBatch(batch, it))
                .then();
    }

    @Override
    @WithSpan
    public Mono<Void> createFromTraces(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> traceIds,
            @NonNull TraceEnrichmentOptions enrichmentOptions) {

        log.info("Creating dataset items from '{}' traces for dataset '{}'", traceIds.size(), datasetId);

        // Verify dataset exists
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> {
                return template.inTransaction(READ_ONLY, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    return dao.findById(datasetId, workspaceId)
                            .orElseThrow(() -> new NotFoundException("Dataset not found: '%s'".formatted(datasetId)));
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }).flatMap(dataset -> {
            // Enrich traces with metadata
            return traceEnrichmentService.enrichTraces(traceIds, enrichmentOptions)
                    .flatMap(enrichedTraces -> {
                        // Convert enriched traces to dataset items
                        List<DatasetItem> datasetItems = enrichedTraces.entrySet().stream()
                                .<DatasetItem>map(entry -> DatasetItem.builder()
                                        .id(idGenerator.generateId())
                                        .source(DatasetItemSource.TRACE)
                                        .traceId(entry.getKey())
                                        .data(entry.getValue())
                                        .build())
                                .toList();

                        // Save dataset items
                        DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, datasetItems);
                        return saveBatch(batch, datasetId);
                    });
        }).then();
    }

    @Override
    @WithSpan
    public Mono<Void> createFromSpans(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> spanIds,
            @NonNull SpanEnrichmentOptions enrichmentOptions) {

        log.info("Creating dataset items from '{}' spans for dataset '{}'", spanIds.size(), datasetId);

        // Verify dataset exists
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> {
                return template.inTransaction(READ_ONLY, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    return dao.findById(datasetId, workspaceId)
                            .orElseThrow(() -> new NotFoundException("Dataset not found: '%s'".formatted(datasetId)));
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }).flatMap(dataset -> {
            // Enrich spans with metadata
            return spanEnrichmentService.enrichSpans(spanIds, enrichmentOptions)
                    .flatMap(enrichedSpans -> {
                        // Convert enriched spans to dataset items
                        List<DatasetItem> datasetItems = enrichedSpans.entrySet().stream()
                                .<DatasetItem>map(entry -> DatasetItem.builder()
                                        .id(idGenerator.generateId())
                                        .source(DatasetItemSource.SPAN)
                                        .spanId(entry.getKey())
                                        .data(entry.getValue())
                                        .build())
                                .toList();

                        // Save dataset items
                        DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, datasetItems);
                        return saveBatch(batch, datasetId);
                    });
        }).then();
    }

    private Mono<UUID> getDatasetId(DatasetItemBatch batch) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Visibility visibility = ctx.get(RequestContext.VISIBILITY);

            return Mono.fromCallable(() -> {

                if (batch.datasetId() == null) {
                    return datasetService.getOrCreate(workspaceId, batch.datasetName(), userName);
                }

                Dataset dataset = datasetService.findById(batch.datasetId(), workspaceId, visibility);

                if (dataset == null) {
                    throw newConflict(
                            "workspace_name from dataset item batch and dataset_id from item does not match");
                }

                return dataset.id();
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    private Throwable failWithError(String error) {
        log.info(error);
        return new ClientErrorException(Response.status(422).entity(new ErrorMessage(List.of(error))).build());
    }

    private ClientErrorException newConflict(String error) {
        log.info(error);
        return new ClientErrorException(Response.status(409).entity(new ErrorMessage(List.of(error))).build());
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> get(@NonNull UUID id) {
        return dao.get(id)
                .flatMap(item -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    Visibility visibility = ctx.get(RequestContext.VISIBILITY);
                    // Verify dataset visibility
                    datasetService.findById(item.datasetId(), workspaceId, visibility);

                    return Mono.just(item);
                }))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Dataset item not found"))));
    }

    @Override
    @WithSpan
    public Mono<Void> patch(@NonNull UUID id, @NonNull DatasetItem item) {
        return get(id)
                .flatMap(existingItem -> {
                    // Build patched item by merging provided fields with existing item
                    // Only non-null fields from the patch are applied
                    var builder = existingItem.toBuilder();

                    // Apply patch fields if provided
                    Optional.ofNullable(item.data()).ifPresent(builder::data);
                    Optional.ofNullable(item.source()).ifPresent(builder::source);
                    Optional.ofNullable(item.traceId()).ifPresent(builder::traceId);
                    Optional.ofNullable(item.spanId()).ifPresent(builder::spanId);
                    Optional.ofNullable(item.tags()).ifPresent(builder::tags);

                    DatasetItem patchedItem = builder.build();

                    // Save the patched item (ClickHouse INSERT replaces existing rows with same ID)
                    DatasetItemBatch batch = new DatasetItemBatch(null, existingItem.datasetId(), List.of(patchedItem));
                    return saveBatch(batch, existingItem.datasetId());
                })
                .then();
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull DatasetItemBatchUpdate batchUpdate) {
        log.info("Batch updating '{}' dataset items", batchUpdate.ids().size());

        return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.update(), batchUpdate.mergeTags())
                .doOnSuccess(__ -> log.info("Completed batch update for '{}' dataset items", batchUpdate.ids().size()));
    }

    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull String workspaceId, @NonNull DatasetItemStreamRequest request,
            Visibility visibility) {
        log.info("Getting dataset items by '{}' on workspaceId '{}'", request, workspaceId);
        return Mono
                .fromCallable(() -> datasetService.findByName(workspaceId, request.datasetName(), visibility))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(dataset -> dao.getItems(dataset.id(), request.steamLimit(), request.lastRetrievedId()));
    }

    @Override
    public Mono<PageColumns> getOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        return dao.getOutputColumns(datasetId, experimentIds)
                .map(columns -> PageColumns.builder().columns(columns).build())
                .switchIfEmpty(Mono.just(PageColumns.empty()));
    }

    @Override
    @WithSpan
    public Mono<Long> saveBatch(@NonNull UUID datasetId, @NonNull List<DatasetItem> items) {
        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        // Create a batch with the items and save it
        DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, items);
        return saveBatch(batch, datasetId);
    }

    private Mono<Long> saveBatch(DatasetItemBatch batch, UUID id) {
        if (batch.items().isEmpty()) {
            return Mono.empty();
        }

        List<DatasetItem> items = addIdIfAbsent(batch);

        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return validateSpans(workspaceId, items)
                    .then(Mono.defer(() -> validateTraces(workspaceId, items)))
                    .then(Mono.defer(() -> dao.save(id, items)));
        });
    }

    private Mono<Void> validateSpans(String workspaceId, List<DatasetItem> items) {
        Set<UUID> spanIds = items.stream()
                .map(DatasetItem::spanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return spanService.validateSpanWorkspace(workspaceId, spanIds)
                .flatMap(valid -> {
                    if (Boolean.FALSE.equals(valid)) {
                        return failWithConflict("span workspace and dataset item workspace does not match");
                    }

                    return Mono.empty();
                });
    }

    private Mono<Boolean> validateTraces(String workspaceId, List<DatasetItem> items) {
        Set<UUID> traceIds = items.stream()
                .map(DatasetItem::traceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return traceService.validateTraceWorkspace(workspaceId, traceIds)
                .flatMap(valid -> {
                    if (Boolean.FALSE.equals(valid)) {
                        return failWithConflict("trace workspace and dataset item workspace does not match");
                    }

                    return Mono.empty();
                });
    }

    private List<DatasetItem> addIdIfAbsent(DatasetItemBatch batch) {
        return batch.items()
                .stream()
                .map(item -> {
                    IdGenerator.validateVersion(item.id(), "dataset_item");
                    return item;
                })
                .toList();
    }

    private <T> Mono<T> failWithConflict(String message) {
        log.info(message);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(message))));
    }

    private NotFoundException failWithNotFound(String message) {
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.empty();
        }

        return dao.delete(ids).then();
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {
        log.info("Finding dataset items with experiment items by '{}', page '{}', size '{}'",
                datasetItemSearchCriteria, page, size);

        // Verify dataset visibility
        datasetService.findById(datasetItemSearchCriteria.datasetId());

        return dao.getItems(datasetItemSearchCriteria, page, size)
                .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
    }

    public Mono<ProjectStats> getExperimentItemsStats(@NonNull UUID datasetId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("Getting experiment items stats for dataset '{}' and experiments '{}' with filters '{}'", datasetId,
                experimentIds, filters);
        return dao.getExperimentItemsStats(datasetId, experimentIds, filters)
                .switchIfEmpty(Mono.just(ProjectStats.empty()))
                .doOnSuccess(stats -> log.info("Found experiment items stats for dataset '{}', count '{}'", datasetId,
                        stats.stats().size()));
    }
}
