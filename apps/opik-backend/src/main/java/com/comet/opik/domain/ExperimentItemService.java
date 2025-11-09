package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.instrumentation.InstrumentationService;
import com.google.common.base.Preconditions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@Slf4j
public class ExperimentItemService {

    private static final AttributeKey<String> WORKSPACE_ID = AttributeKey.stringKey("workspace_id");

    private final @NonNull ExperimentItemDAO experimentItemDAO;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull DatasetItemDAO datasetItemDAO;
    private final @NonNull InstrumentationService instrumentationService;

    // Metrics
    private DoubleHistogram experimentItemBatchSizeHistogram;

    @Inject
    public ExperimentItemService(
            @NonNull ExperimentItemDAO experimentItemDAO,
            @NonNull ExperimentService experimentService,
            @NonNull DatasetItemDAO datasetItemDAO,
            @NonNull InstrumentationService instrumentationService) {
        this.experimentItemDAO = experimentItemDAO;
        this.experimentService = experimentService;
        this.datasetItemDAO = datasetItemDAO;
        this.instrumentationService = instrumentationService;

        // Initialize metrics
        this.experimentItemBatchSizeHistogram = instrumentationService.createHistogram(
                "opik.experiment_items.batch_size",
                "Number of experiment items in each batch insert",
                "items");

        log.info("ExperimentItemService initialized with metrics instrumentation");
    }

    public Mono<Void> create(Set<ExperimentItem> experimentItems) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentItems),
                "Argument 'experimentItems' must not be empty");

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var experimentItemsWithValidIds = addIdIfAbsentAndValidateIt(experimentItems, workspaceId);

            log.info("Creating experiment items, count '{}'", experimentItemsWithValidIds.size());
            return experimentItemDAO.insert(experimentItemsWithValidIds)
                    .doOnSuccess(count -> {
                        // Record metric: experiment items batch size
                        recordExperimentItemBatchSizeMetric(experimentItemsWithValidIds.size(), workspaceId);
                    })
                    .then();
        });
    }

    /**
     * Records the opik.experiment_items.batch_size histogram metric.
     */
    private void recordExperimentItemBatchSizeMetric(int batchSize, String workspaceId) {
        Attributes attributes = Attributes.builder()
                .put(WORKSPACE_ID, workspaceId)
                .build();

        instrumentationService.recordHistogram(experimentItemBatchSizeHistogram, batchSize, attributes);
        log.debug("Recorded metric: opik.experiment_items.batch_size={} for workspace='{}'",
                batchSize, workspaceId);
    }

    private Set<ExperimentItem> addIdIfAbsentAndValidateIt(Set<ExperimentItem> experimentItems, String workspaceId) {
        validateExperimentsWorkspace(experimentItems, workspaceId);

        validateDatasetItemsWorkspace(experimentItems, workspaceId);

        return experimentItems.stream()
                .map(item -> {
                    IdGenerator.validateVersion(item.id(), "Experiment Item");
                    IdGenerator.validateVersion(item.experimentId(), "Experiment Item experiment");
                    IdGenerator.validateVersion(item.datasetItemId(), "Experiment Item datasetItem");
                    IdGenerator.validateVersion(item.traceId(), "Experiment Item trace");
                    return item;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private void validateExperimentsWorkspace(Set<ExperimentItem> experimentItems, String workspaceId) {
        Set<UUID> experimentIds = experimentItems
                .stream()
                .map(ExperimentItem::experimentId)
                .collect(Collectors.toSet());

        boolean allExperimentsBelongToWorkspace = Boolean.TRUE
                .equals(experimentService.validateExperimentWorkspace(workspaceId, experimentIds)
                        .block());

        if (!allExperimentsBelongToWorkspace) {
            throw createConflict("Upserting experiment item with 'experiment_id' not belonging to the workspace");
        }
    }

    private ClientErrorException createConflict(String message) {
        log.info(message);
        return new ClientErrorException(message, Response.Status.CONFLICT);
    }

    private void validateDatasetItemsWorkspace(Set<ExperimentItem> experimentItems, String workspaceId) {
        Set<UUID> datasetItemIds = experimentItems
                .stream()
                .map(ExperimentItem::datasetItemId)
                .collect(Collectors.toSet());

        boolean allDatasetItemsBelongToWorkspace = Boolean.TRUE
                .equals(validateDatasetItemWorkspace(workspaceId, datasetItemIds)
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        if (!allDatasetItemsBelongToWorkspace) {
            throw createConflict("Upserting experiment item with 'dataset_item_id' not belonging to the workspace");
        }
    }

    private Mono<Boolean> validateDatasetItemWorkspace(String workspaceId, Set<UUID> datasetItemIds) {
        if (datasetItemIds.isEmpty()) {
            return Mono.just(true);
        }

        return datasetItemDAO.getDatasetItemWorkspace(datasetItemIds)
                .map(datasetItemWorkspace -> datasetItemWorkspace.stream()
                        .allMatch(datasetItem -> workspaceId.equals(datasetItem.workspaceId())));
    }

    public Mono<ExperimentItem> get(@NonNull UUID id) {
        log.info("Getting experiment item by id '{}'", id);
        return experimentItemDAO.get(id)
                .switchIfEmpty(Mono.error(newNotFoundException(id)));
    }

    private NotFoundException newNotFoundException(UUID id) {
        String message = "Not found experiment item with id '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }

    public Flux<ExperimentItem> getExperimentItems(@NonNull ExperimentItemSearchCriteria criteria) {
        log.info("Getting experiment items by '{}'", criteria);
        return experimentService.findByName(criteria.experimentName())
                .subscribeOn(Schedulers.boundedElastic())
                .collect(Collectors.mapping(Experiment::id, Collectors.toUnmodifiableSet()))
                .flatMapMany(experimentIds -> experimentItemDAO.getItems(
                        experimentIds, criteria));
    }

    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids),
                "Argument 'ids' must not be empty");

        log.info("Deleting experiment items, count '{}'", ids.size());
        return experimentItemDAO.delete(ids).then();
    }
}
