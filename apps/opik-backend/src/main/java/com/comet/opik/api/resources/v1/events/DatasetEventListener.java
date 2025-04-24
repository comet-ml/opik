package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
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

    @Inject
    public DatasetEventListener(DatasetService datasetService, ExperimentService experimentService) {
        this.datasetService = datasetService;
        this.experimentService = experimentService;
    }

    @Subscribe
    public void onExperimentCreated(ExperimentCreated event) {
        log.info("Recording experiment for dataset '{}'", event.datasetId());

        datasetService.recordExperiments(Set.of(new DatasetLastExperimentCreated(event.datasetId(), event.createdAt())))
                .contextWrite(ctx -> setContext(event, ctx))
                .block();

        log.info("Recorded experiment for dataset '{}'", event.datasetId());
    }

    private static Context setContext(BaseEvent event, Context ctx) {
        return ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                .put(RequestContext.USER_NAME, event.userName());
    }

    @Subscribe
    public void onExperimentsDeleted(ExperimentsDeleted event) {

        if (event.datasetIds().isEmpty()) {
            log.info("No datasets found for event '{}'", event);
            return;
        }

        Set<UUID> updatedDatasets = updateAndGetDatasetsWithExperiments(event);

        updateDatasetsWithoutExperiments(event, updatedDatasets);
    }

    private Set<UUID> updateAndGetDatasetsWithExperiments(ExperimentsDeleted event) {
        return experimentService.getMostRecentCreatedExperimentFromDatasets(event.datasetIds())
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
        Flux.fromIterable(SetUtils.difference(event.datasetIds(), updatedDatasets))
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
}
