package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.events.BaseEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
public class DatasetEventListener {

    private final DatasetService datasetService;
    private final ExperimentService experimentService;

    @Inject
    public DatasetEventListener(EventBus eventBus, DatasetService datasetService, ExperimentService experimentService) {
        this.datasetService = datasetService;
        this.experimentService = experimentService;
        eventBus.register(this);
    }

    @Subscribe
    public void onExperimentCreated(ExperimentCreated event) {
        log.info("Recording experiment for dataset '{}'", event.datasetId());

        datasetService.recordExperiment(event.datasetId(), event.createdAt())
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
                .publishOn(Schedulers.boundedElastic())
                .flatMap(dto -> {
                    log.info("Updating dataset '{}' with last experiment created time", dto.datasetId());

                    return datasetService.recordExperiment(dto.datasetId(), dto.experimentCreatedAt())
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    log.error("Failed to update dataset '{}' with last experiment created time",
                                            dto.datasetId());
                                } else {
                                    log.info("Updated dataset '{}' with last experiment created time", dto.datasetId());
                                }
                            })
                            .then(Mono.just(dto.datasetId()));
                })
                .contextWrite(ctx -> setContext(event, ctx))
                .collect(Collectors.toSet())
                .block();
    }

    private void updateDatasetsWithoutExperiments(ExperimentsDeleted event, Set<UUID> updatedDatasets) {
        Flux.fromIterable(SetUtils.difference(event.datasetIds(), updatedDatasets))
                .flatMap(datasetId -> {
                    log.info("Updating dataset '{}' with last experiment created time", datasetId);

                    return datasetService.recordExperiment(datasetId, null)
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    log.error("Failed to update dataset '{}' with last experiment created time",
                                            datasetId);
                                } else {
                                    log.info("Updated dataset '{}' with last experiment created time", datasetId);
                                }
                            });

                })
                .contextWrite(ctx -> setContext(event, ctx))
                .collectList()
                .block();
    }
}
