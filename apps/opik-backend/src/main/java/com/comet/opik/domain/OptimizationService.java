package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationSearchCriteria;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.events.OptimizationCreated;
import com.comet.opik.api.events.OptimizationsDeleted;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(OptimizationServiceImpl.class)
public interface OptimizationService {

    Mono<UUID> upsert(@NonNull Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<Optimization.OptimizationPage> find(int page, int size, OptimizationSearchCriteria searchCriteria);

    Mono<Void> delete(@NonNull Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds);

    Mono<Long> update(UUID commentId, OptimizationUpdate update);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class OptimizationServiceImpl implements OptimizationService {

    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull NameGenerator nameGenerator;
    private final @NonNull EventBus eventBus;

    @Override
    @WithSpan
    public Mono<Optimization> getById(@NonNull UUID id) {
        log.info("Getting optimization by id '{}'", id);
        return optimizationDAO.getById(id)
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    return Mono.just(enrichOptimizations(List.of(optimization), workspaceId).getFirst());
                }))
                .switchIfEmpty(Mono.defer(
                        () -> Mono.error(new NotFoundException("Not found optimization with id '%s'".formatted(id)))));
    }

    @Override
    @WithSpan
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return optimizationDAO.find(page, size, searchCriteria)
                .flatMap(optimizationPage -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    return Mono.just(optimizationPage.toBuilder()
                            .content(enrichOptimizations(optimizationPage.content(), workspaceId)).build());
                }));
    }

    @Override
    @WithSpan
    public Mono<UUID> upsert(@NonNull Optimization optimization) {
        UUID id = optimization.id() == null ? idGenerator.generateId() : optimization.id();
        IdGenerator.validateVersion(id, "Optimization");
        var name = StringUtils.getIfBlank(optimization.name(), nameGenerator::generateName);

        return datasetService.getOrCreateDataset(optimization.datasetName())
                .flatMap(datasetId -> {
                    var newOptimization = optimization.toBuilder()
                            .id(id)
                            .name(name)
                            .datasetId(datasetId)
                            .build();

                    return makeMonoContextAware((userName, workspaceId) -> optimizationDAO.upsert(newOptimization)
                            .thenReturn(newOptimization.id())
                            // The event is posted only when the experiment is successfully created.
                            .doOnSuccess(experimentId -> postOptimizationCreatedEvent(newOptimization, workspaceId,
                                    userName)))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                // If a conflict occurs, we just return the id of the existing experiment.
                // If any other error occurs, we throw it. The event is not posted for both cases.
                .onErrorResume(throwable -> handleCreateError(throwable, id));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return optimizationDAO.getOptimizationDatasetIds(ids)
                .flatMap(optimizationDatasetIds -> Mono.deferContextual(ctx -> optimizationDAO.delete(ids)
                        .doOnSuccess(unused -> eventBus.post(new OptimizationsDeleted(
                                optimizationDatasetIds.stream()
                                        .map(DatasetEventInfoHolder::datasetId)
                                        .collect(Collectors.toSet()),
                                ctx.get(RequestContext.WORKSPACE_ID),
                                ctx.get(RequestContext.USER_NAME))))))
                .then();
    }

    @Override
    @WithSpan
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return optimizationDAO.getMostRecentCreatedExperimentFromDatasets(datasetIds);
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        if (update.name() == null && update.status() == null) {
            return Mono.empty();
        }

        return optimizationDAO.getById(id)
                .switchIfEmpty(Mono.error(failWithNotFound("Optimization", id)))
                .then(Mono.defer(() -> optimizationDAO.update(id, update)));
    }

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Mono.empty();
        }

        return optimizationDAO.updateDatasetDeleted(datasetIds);
    }

    private Mono<UUID> handleCreateError(Throwable throwable, UUID id) {
        if (throwable instanceof ClickHouseException
                && throwable.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && throwable.getMessage().contains("_CAST(id, FixedString(36))")) {
            log.warn("Already exists optimization with id '{}'", id);
            return Mono.just(id);
        }
        log.error("Unexpected exception creating optimization with id '{}'", id);
        return Mono.error(throwable);
    }

    private void postOptimizationCreatedEvent(Optimization newOptimization, String workspaceId, String userName) {
        log.info("Posting optimization created event for optimization id '{}', datasetId '{}', workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
        eventBus.post(new OptimizationCreated(
                newOptimization.id(),
                newOptimization.datasetId(),
                Instant.now(),
                workspaceId,
                userName));
        log.info("Posted optimization created event for optimization id '{}', datasetId '{}', workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
    }

    private List<Optimization> enrichOptimizations(List<Optimization> optimizations, String workspaceId) {
        var ids = optimizations.stream().map(Optimization::datasetId).collect(Collectors.toUnmodifiableSet());
        var datasetMap = datasetService.findByIds(ids, workspaceId)
                .stream().collect(Collectors.toMap(Dataset::id, Function.identity()));

        return optimizations.stream()
                .map(optimization -> optimization.toBuilder()
                        .datasetName(Optional
                                .ofNullable(datasetMap.get(optimization.datasetId()))
                                .map(Dataset::name)
                                .orElse(null))
                        .build())
                .toList();
    }
}
