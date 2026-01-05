package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ExperimentItemService {

    private final @NonNull ExperimentItemDAO experimentItemDAO;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull DatasetItemDAO datasetItemDAO;
    private final @NonNull DatasetItemVersionDAO datasetItemVersionDAO;
    private final @NonNull FeatureFlags featureFlags;

    public Mono<Void> create(Set<ExperimentItem> experimentItems) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentItems),
                "Argument 'experimentItems' must not be empty");

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            var experimentItemsWithValidIds = validateExperimentItemIdsAndWorkspace(experimentItems, workspaceId,
                    userName);

            log.info("Creating experiment items, count '{}'", experimentItemsWithValidIds.size());
            return experimentItemDAO.insert(experimentItemsWithValidIds)
                    .then();
        });
    }

    private Set<ExperimentItem> validateExperimentItemIdsAndWorkspace(
            Set<ExperimentItem> experimentItems, String workspaceId, String userName) {
        validateExperimentsWorkspace(experimentItems, workspaceId);

        validateDatasetItemsWorkspace(experimentItems, workspaceId, userName);

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

    private void validateDatasetItemsWorkspace(
            Set<ExperimentItem> experimentItems, String workspaceId, String userName) {
        Set<UUID> datasetItemIds = experimentItems
                .stream()
                .map(ExperimentItem::datasetItemId)
                .collect(Collectors.toSet());

        boolean allDatasetItemsBelongToWorkspace = Boolean.TRUE
                .equals(validateDatasetItemWorkspace(workspaceId, datasetItemIds)
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                                .put(RequestContext.USER_NAME, userName))
                        .block());

        if (!allDatasetItemsBelongToWorkspace) {
            throw createConflict("Upserting experiment item with 'dataset_item_id' not belonging to the workspace");
        }
    }

    private Mono<Boolean> validateDatasetItemWorkspace(String workspaceId, Set<UUID> datasetItemIds) {
        if (datasetItemIds.isEmpty()) {
            return Mono.just(true);
        }

        // When versioning is enabled, dataset item IDs are row IDs from dataset_item_versions
        // When versioning is disabled, dataset item IDs are from dataset_items (legacy table)
        // We need to check both tables to support backward compatibility
        if (featureFlags.isDatasetVersioningEnabled()) {
            // Check versioned table
            return datasetItemVersionDAO.getDatasetItemWorkspace(datasetItemIds)
                    .map(items -> items.stream()
                            .allMatch(item -> workspaceId.equals(item.workspaceId())));
        }

        // Legacy mode: only check dataset_items table
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
