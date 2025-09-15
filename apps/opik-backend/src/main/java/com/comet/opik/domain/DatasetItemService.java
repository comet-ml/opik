package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.api.error.ErrorMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import com.comet.opik.api.error.IdentifierMismatchException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;

@ImplementedBy(DatasetItemServiceImpl.class)
public interface DatasetItemService {

    Mono<Void> save(DatasetItemBatch batch);

    Mono<DatasetItem> get(UUID id);

    Mono<Void> delete(List<UUID> ids);

    Mono<DatasetItemPage> getItems(UUID datasetId, int page, int size, boolean truncate);

    Mono<DatasetItemPage> getItems(int page, int size, DatasetItemSearchCriteria datasetItemSearchCriteria);

    Flux<DatasetItem> getItems(String workspaceId, DatasetItemStreamRequest request, Visibility visibility);

    Mono<PageColumns> getOutputColumns(UUID datasetId, Set<UUID> experimentIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemServiceImpl implements DatasetItemService {

    private final @NonNull DatasetItemDAO dao;
    private final @NonNull DatasetService datasetService;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;

    @Override
    @WithSpan
    public Mono<Void> save(@NonNull DatasetItemBatch batch) {
        if (batch.datasetId() == null && batch.datasetName() == null) {
            return Mono.error(failWithError("dataset_id or dataset_name must be provided"));
        }

        return getDatasetId(batch)
                .flatMap(it -> saveBatch(batch, it))
                .then();
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

    private Mono<Long> saveBatch(DatasetItemBatch batch, UUID id) {
        if (batch.items().isEmpty()) {
            return Mono.empty();
        }

        // Create a mutable copy of the items list for potential enrichment
        List<DatasetItem> items = new ArrayList<>(addIdIfAbsent(batch));

        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return validateSpans(workspaceId, items)
                    .then(Mono.defer(() -> validateTraces(workspaceId, items)))
                    .then(Mono.defer(() -> {
                        // If metadata inclusion is requested, enrich items with trace metadata
                        if (Boolean.TRUE.equals(batch.includeTraceMetadata())) {
                            log.info("Enriching dataset items with trace metadata for '{}' items", items.size());
                            return enrichItemsWithTraceMetadata(workspaceId, items)
                                    .then(Mono.defer(() -> dao.save(id, items)));
                        } else {
                            log.info("Saving dataset items without trace metadata for '{}' items", items.size());
                            return dao.save(id, items);
                        }
                    }));
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

    /**
     * Enriches dataset items with trace metadata by fetching trace details and adding them to the item data.
     * 
     * @param workspaceId the workspace ID for context
     * @param items the list of dataset items to enrich (modified in place)
     * @return a Mono that completes when all items have been enriched
     */
    private Mono<Void> enrichItemsWithTraceMetadata(String workspaceId, List<DatasetItem> items) {
        // Get unique trace IDs from items
        Set<UUID> traceIds = items.stream()
                .map(DatasetItem::traceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        log.debug("Found '{}' unique trace IDs to enrich", traceIds.size());

        if (traceIds.isEmpty()) {
            log.warn("No trace IDs found in dataset items, skipping metadata enrichment");
            return Mono.empty();
        }

        // Fetch trace details for all traces
        return Flux.fromIterable(traceIds)
                .flatMap(traceId -> traceService.get(traceId)
                        .map(trace -> {
                            log.debug("Successfully fetched trace '{}' with metadata", traceId);
                            return Map.entry(traceId, trace);
                        })
                        .onErrorResume(error -> {
                            log.warn("Failed to fetch trace details for trace '{}': {}", traceId, error.getMessage());
                            return Mono.empty();
                        }))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doOnNext(traceMap -> {
                    log.debug("Successfully fetched '{}' traces, enriching dataset items", traceMap.size());
                    // Enrich items with trace metadata by replacing them in the list
                    for (int i = 0; i < items.size(); i++) {
                        DatasetItem item = items.get(i);
                        if (item.traceId() != null && traceMap.containsKey(item.traceId())) {
                            Trace trace = traceMap.get(item.traceId());
                            DatasetItem enrichedItem = enrichItemWithTraceMetadata(item, trace);
                            log.debug("Enriched dataset item '{}' with trace metadata", item.id());
                            items.set(i, enrichedItem);
                        }
                    }
                })
                .then();
    }

    /**
     * Creates a new DatasetItem with enriched data containing trace metadata.
     * 
     * @param item the original dataset item
     * @param trace the trace containing metadata to add
     * @return a new DatasetItem with enriched data
     */
    private DatasetItem enrichItemWithTraceMetadata(DatasetItem item, Trace trace) {
        // Create a mutable copy of the data map
        Map<String, JsonNode> enrichedData = new HashMap<>(item.data());

        // Add trace metadata to the data map
        if (trace.tags() != null && !trace.tags().isEmpty()) {
            enrichedData.put("trace_tags", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.tags())));
        }

        if (trace.comments() != null && !trace.comments().isEmpty()) {
            enrichedData.put("trace_comments", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.comments())));
        }

        if (trace.feedbackScores() != null && !trace.feedbackScores().isEmpty()) {
            enrichedData.put("trace_feedback_scores", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.feedbackScores())));
        }

        if (trace.guardrailsValidations() != null && !trace.guardrailsValidations().isEmpty()) {
            enrichedData.put("trace_guardrails_validations", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.guardrailsValidations())));
        }

        if (trace.metadata() != null) {
            enrichedData.put("trace_metadata", trace.metadata());
        }

        if (trace.usage() != null && !trace.usage().isEmpty()) {
            enrichedData.put("trace_usage", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.usage())));
        }

        if (trace.totalEstimatedCost() != null) {
            enrichedData.put("trace_total_estimated_cost", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.totalEstimatedCost())));
        }

        // spanCount and llmSpanCount are primitive int, so they can't be null
        enrichedData.put("trace_span_count", JsonUtils.getJsonNodeFromStringWithFallback(
                JsonUtils.writeValueAsString(trace.spanCount())));
        enrichedData.put("trace_llm_span_count", JsonUtils.getJsonNodeFromStringWithFallback(
                JsonUtils.writeValueAsString(trace.llmSpanCount())));

        if (trace.duration() != null) {
            enrichedData.put("trace_duration", JsonUtils.getJsonNodeFromStringWithFallback(
                    JsonUtils.writeValueAsString(trace.duration())));
        }

        // Return a new DatasetItem with enriched data
        return item.toBuilder().data(enrichedData).build();
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
    public Mono<DatasetItemPage> getItems(@NonNull UUID datasetId, int page, int size, boolean truncate) {
        // Verify dataset visibility
        datasetService.findById(datasetId);

        return dao.getItems(datasetId, page, size, truncate)
                .defaultIfEmpty(DatasetItemPage.empty(page));
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {
        log.info("Finding dataset items with experiment items by '{}', page '{}', size '{}'",
                datasetItemSearchCriteria, page, size);
        return dao.getItems(datasetItemSearchCriteria, page, size);
    }
}
