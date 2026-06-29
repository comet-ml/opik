package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@EagerSingleton
@Slf4j
public class ProjectEventListener {
    private final ProjectService projectService;
    private final TraceService traceService;

    @Inject
    public ProjectEventListener(ProjectService projectService, TraceService traceService) {
        this.projectService = projectService;
        this.traceService = traceService;
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        updateProjectsLastUpdatedTraceAt(event.workspaceId(), event.projectIds());
    }

    @Subscribe
    public void onTracesUpdated(TracesUpdated event) {
        updateProjectsLastUpdatedTraceAt(event.workspaceId(), event.projectIds());
    }

    private void updateProjectsLastUpdatedTraceAt(String workspaceId, Set<UUID> projectIds) {
        log.info("Recording last traces for projects '{}'", projectIds);

        // The recorded last_updated_trace_at is the lower bound for the ClickHouse scan: only traces newer than what
        // we already stored can move it forward, so we skip rescanning the project's full history.
        Instant lastUpdatedAfter = resolveLastUpdatedAfter(workspaceId, projectIds);

        traceService.getLastUpdatedTraceAt(projectIds, workspaceId, lastUpdatedAfter)
                .flatMap(lastTraceByProjectId -> Mono.fromRunnable(() -> projectService.recordLastUpdatedTrace(
                        workspaceId,
                        lastTraceByProjectId.entrySet().stream()
                                .map(entry -> new ProjectIdLastUpdated(entry.getKey(), entry.getValue())).toList())))
                .block();

        log.info("Recorded last traces for projects '{}'", projectIds);
    }

    private Instant resolveLastUpdatedAfter(String workspaceId, Set<UUID> projectIds) {
        List<Project> projects = projectService.findByIds(workspaceId, projectIds);

        // A project without a recorded timestamp (e.g. its first traces) must be scanned from the start, so we can
        // only apply the lower bound when every project already has one; otherwise scan without it.
        if (projects.size() < projectIds.size()
                || projects.stream().anyMatch(project -> project.lastUpdatedTraceAt() == null)) {
            return null;
        }

        return projects.stream()
                .map(Project::lastUpdatedTraceAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
    }
}
