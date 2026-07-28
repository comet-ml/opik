package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.ProjectLastUpdatedTraceBufferService;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maintains {@code projects.last_updated_trace_at}, the marker behind the "last active" project sort.
 * <p>
 * Because the MySQL write only moves it forward ({@code WHERE last_updated_trace_at < :lastUpdatedAt}),
 * any new trace-write path must publish {@link TracesCreated}/{@link TracesUpdated} for the marker to keep advancing.
 */
@EagerSingleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ProjectEventListener {

    private final ProjectLastUpdatedTraceBufferService projectLastUpdatedTraceBufferService;

    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        // Event publish time; fallback for any trace persisted with a server-generated last_updated_at.
        var lastUpdatedAtByProject = event.traces().stream()
                .collect(Collectors.toMap(
                        Trace::projectId,
                        trace -> trace.lastUpdatedAt() != null ? trace.lastUpdatedAt() : event.createdAt(),
                        (left, right) -> left.isAfter(right) ? left : right));
        recordLastUpdatedTrace(event.workspaceId(), lastUpdatedAtByProject);
    }

    @Subscribe
    public void onTracesUpdated(@NonNull TracesUpdated event) {
        // Updates set last_updated_at server-side and the event does not carry it, so use the event publish time.
        var lastUpdatedAtByProject = event.projectIds().stream()
                .collect(Collectors.toMap(Function.identity(), _ -> event.createdAt()));
        recordLastUpdatedTrace(event.workspaceId(), lastUpdatedAtByProject);
    }

    private void recordLastUpdatedTrace(String workspaceId, Map<UUID, Instant> lastUpdatedAtByProject) {
        if (lastUpdatedAtByProject.isEmpty()) {
            return;
        }
        var lastUpdatedTraces = lastUpdatedAtByProject.entrySet().stream()
                .map(entry -> ProjectIdLastUpdated.builder()
                        .id(entry.getKey())
                        .lastUpdatedAt(entry.getValue())
                        .build())
                .collect(Collectors.toUnmodifiableSet());
        projectLastUpdatedTraceBufferService.record(workspaceId, lastUpdatedTraces);
        var projectIds = lastUpdatedAtByProject.keySet();
        log.info("Recorded last traces for projects '{}'", projectIds);
    }
}
