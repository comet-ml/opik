package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

@EagerSingleton
@Slf4j
public class ProjectEventListener {
    private final ProjectService projectService;
    private final TraceService traceService;

    @Inject
    public ProjectEventListener(EventBus eventBus, ProjectService projectService, TraceService traceService) {
        this.projectService = projectService;
        this.traceService = traceService;
        eventBus.register(this);
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        log.info("Recording last traces for projects '{}'", event.projectIds());

        traceService.getLastUpdatedTraceAt(event.projectIds(), event.workspaceId())
                .flatMap(lastTraceByProjectId -> Mono.fromRunnable(() -> projectService.recordLastUpdatedTrace(
                        event.workspaceId(),
                        lastTraceByProjectId.entrySet().stream()
                                .map(entry -> new ProjectIdLastUpdated(entry.getKey(), entry.getValue())).toList())))
                .block();

        log.info("Recorded last traces for projects '{}'", event.projectIds());
    }

}
