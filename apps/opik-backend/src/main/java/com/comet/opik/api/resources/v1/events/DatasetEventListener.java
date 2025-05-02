package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.events.DatasetsDeleted;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.events.OptimizationCreated;
import com.comet.opik.api.events.OptimizationsDeleted;
import com.comet.opik.domain.DatasetEventInfoHolder;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.OptimizationService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.events.BaseEvent;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
public class DatasetEventListener {

    private final DatasetService datasetService;
    private final ExperimentService experimentService;
    private final OptimizationService optimizationService;

    @Inject
    public DatasetEventListener(DatasetService datasetService, ExperimentService experimentService,
            OptimizationService optimizationService) {
        this.datasetService = datasetService;
        this.experimentService = experimentService;
        this.optimizationService = optimizationService;
    }

    @Subscribe
    public void onExperimentCreated(ExperimentCreated event) {
        log.info("Recording experiment for dataset '{}', experiment type '{}'", event.datasetId(), event.type());
        if (event.type() != ExperimentType.REGULAR) {
            log.info("Skipping experiment type is not regular for event '{}'", event);
            return;
        }

        datasetService.recordExperiments(Set.of(new DatasetLastExperimentCreated(event.datasetId(), event.createdAt())))
                .contextWrite(ctx -> setContext(event, ctx))
                .block();

        log.info("Recorded experiment for dataset '{}', experiment type '{}'", event.datasetId(), event.type());
    }

    @Subscribe
    public void onOptimizationCreated(OptimizationCreated event) {
        log.info("Recording optimization with id '{}' for dataset '{}'", event.optimizationId(), event.datasetId());

        datasetService
                .recordOptimizations(Set.of(new DatasetLastOptimizationCreated(event.datasetId(), event.createdAt())))
                .contextWrite(ctx -> setContext(event, ctx))
                .block();

        log.info("Recorded optimization with id '{}' for dataset '{}'", event.optimizationId(), event.datasetId());
    }

    private static Context setContext(BaseEvent event, Context ctx) {
        return ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                .put(RequestContext.USER_NAME, event.userName());
    }

    @Subscribe
    public void onExperimentsDeleted(ExperimentsDeleted event) {

        if (event.datasetInfo().isEmpty()) {
            log.info("No datasets found for ExperimentsDeleted event '{}'", event);
            return;
        }

        Set<UUID> updatedDatasets = updateAndGetDatasetsWithExperiments(event);

        updateDatasetsWithoutExperiments(event, updatedDatasets);
    }

    @Subscribe
    public void onOptimizationsDeleted(OptimizationsDeleted event) {

        if (event.datasetIds().isEmpty()) {
            log.info("No datasets found for OptimizationsDeleted event '{}'", event);
            return;
        }

        Set<UUID> updatedDatasets = updateAndGetDatasetsWithOptimizations(event);

        updateDatasetsWithoutOptimizations(event, updatedDatasets);
    }

    @Subscribe
    public void onDatasetsDeleted(DatasetsDeleted event) {

        if (event.datasetIds().isEmpty()) {
            log.info("No datasets found for DatasetsDeleted event '{}'", event);
            return;
        }

        optimizationService.updateDatasetDeleted(event.datasetIds())
                .contextWrite(ctx -> setContext(event, ctx))
                .block();
    }

    private Set<UUID> updateAndGetDatasetsWithExperiments(ExperimentsDeleted event) {
        return experimentService
                .getMostRecentCreatedExperimentFromDatasets(getDatasetIds(event, ExperimentType.REGULAR))
                .collect(Collectors.toSet())
                .flatMap(datasets -> {
                    log.info("Updating datasets '{}' with last experiment created time", datasets);

                    if (datasets.isEmpty()) {
                        return Mono.just(new HashSet<UUID>());
                    }

                    return datasetService.recordExperiments(datasets)
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    log.error("Failed to update datasets '{}' with last experiment created time",
                                            datasets);
                                } else {
                                    log.info("Updated datasets '{}' with last experiment created time", datasets);
                                }
                            })
                            .then(Mono.just(datasets.stream().map(DatasetLastExperimentCreated::datasetId)
                                    .collect(Collectors.toSet())));
                })
                .contextWrite(ctx -> setContext(event, ctx))
                .block();
    }

    private void updateDatasetsWithoutExperiments(ExperimentsDeleted event, Set<UUID> updatedDatasets) {
        Flux.fromIterable(SetUtils.difference(getDatasetIds(event, ExperimentType.REGULAR), updatedDatasets))
                .map(datasetId -> new DatasetLastExperimentCreated(datasetId, null))
                .collect(Collectors.toSet())
                .flatMap(datasets -> {
                    log.info("Updating datasets '{}' with last experiment created time null", datasets);

                    if (datasets.isEmpty()) {
                        return Mono.empty();
                    }

                    return datasetService.recordExperiments(datasets)
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    log.error("Failed to update dataset '{}' with last experiment created time null",
                                            datasets);
                                } else {
                                    log.info("Updated dataset '{}' with last experiment created time", datasets);
                                }
                            });

                })
                .contextWrite(ctx -> setContext(event, ctx))
                .block();
    }

    private Set<UUID> updateAndGetDatasetsWithOptimizations(OptimizationsDeleted event) {
        return optimizationService.getMostRecentCreatedOptimizationFromDatasets(event.datasetIds())
                .collect(Collectors.toSet())
                .flatMap(datasets -> {
                    log.info("Updating datasets '{}' with last optimization created time", datasets);

                    if (datasets.isEmpty()) {
                        return Mono.just(new HashSet<UUID>());
                    }

                    return datasetService.recordOptimizations(datasets)
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    log.error("Failed to update datasets '{}' with last optimization created time",
                                            datasets);
                                } else {
                                    log.info("Updated datasets '{}' with last optimization created time", datasets);
                                }
                            })
                            .then(Mono.just(datasets.stream().map(DatasetLastOptimizationCreated::datasetId)
                                    .collect(Collectors.toSet())));
                })
                .contextWrite(ctx -> setContext(event, ctx))
                .block();
    }

    private void updateDatasetsWithoutOptimizations(OptimizationsDeleted event, Set<UUID> updatedDatasets) {
        Flux.fromIterable(SetUtils.difference(event.datasetIds(), updatedDatasets))
                .map(datasetId -> new DatasetLastOptimizationCreated(datasetId, null))
                .collect(Collectors.toSet())
                .flatMap(datasets -> {
                    log.info("Updating datasets '{}' with last optimization created time null", datasets);

                    if (datasets.isEmpty()) {
                        return Mono.empty();
                    }

                    return datasetService.recordOptimizations(datasets)
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    log.error("Failed to update dataset '{}' with last optimization created time null",
                                            datasets);
                                } else {
                                    log.info("Updated dataset '{}' with last optimization created time", datasets);
                                }
                            });

                })
                .contextWrite(ctx -> setContext(event, ctx))
                .block();
    }

    private Set<UUID> getDatasetIds(ExperimentsDeleted event, ExperimentType type) {
        return event.datasetInfo().stream()
                .filter(datasetInfoHolder -> datasetInfoHolder.type() == type)
                .map(DatasetEventInfoHolder::datasetId)
                .collect(Collectors.toSet());
    }
}
