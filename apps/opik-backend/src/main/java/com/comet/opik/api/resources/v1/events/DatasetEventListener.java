package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentCreated;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

@EagerSingleton
@Slf4j
public class DatasetEventListener {

    private final DatasetService datasetService;

    @Inject
    public DatasetEventListener(EventBus eventBus, DatasetService datasetService) {
        this.datasetService = datasetService;
        eventBus.register(this);
    }

    @Subscribe
    public void onExperimentCreated(ExperimentCreated event) {
        log.info("Recording experiment for dataset '{}'", event.datasetId());

        datasetService.recordExperiment(event.datasetId(), event.experimentId(), event.createdAt())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .block();

        log.info("Recorded experiment for dataset '{}'", event.datasetId());
    }

}
