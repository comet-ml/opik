package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.Project;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
public class BiEventListener {

    public static final String FIRST_TRACE_REPORT_BI_EVENT = "opik_os_first_trace_created";

    private final UsageReportService usageReportService;
    private final ProjectService projectService;
    private final TraceService traceService;
    private final LockService lockService;
    private final OpikConfiguration config;
    private final BiEventService biEventService;

    @Inject
    public BiEventListener(@NonNull ProjectService projectService,
            @NonNull UsageReportService usageReportService, @NonNull TraceService traceService,
            @NonNull OpikConfiguration config, @NonNull LockService lockService,
            @NonNull BiEventService biEventService) {
        this.projectService = projectService;
        this.traceService = traceService;
        this.config = config;
        this.usageReportService = usageReportService;
        this.lockService = lockService;
        this.biEventService = biEventService;
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {

        if (!config.getUsageReport().isEnabled()) {
            return;
        }

        checkIfItIsFirstTraceAndReport(event.workspaceId(), event);
    }

    private void checkIfItIsFirstTraceAndReport(String workspaceId, TracesCreated event) {
        if (usageReportService.isFirstTraceReport()) {
            return;
        }

        if (event.traces().isEmpty()) {
            log.warn("No trace ids found for event '{}'", event);
            return;
        }

        Set<UUID> demoProjectIds = projectService.findByNames(workspaceId, DemoData.PROJECTS)
                .stream()
                .map(Project::id)
                .collect(Collectors.toSet());

        Set<UUID> projectIds = new HashSet<>(event.projectIds());
        projectIds.removeAll(demoProjectIds);

        if (projectIds.isEmpty()) {
            log.info("No project ids found for event");
            return;
        }

        long traces = traceService.countTraces(projectIds)
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, event.userName()))
                .block();

        if (traces <= 0) {
            log.info("No traces found for event '{}'", event);
            return;
        }

        lockService.executeWithLockCustomExpire(
                new LockService.Lock(FIRST_TRACE_REPORT_BI_EVENT),
                Mono.fromRunnable(() -> sendBiEvent(workspaceId, traces)),
                Duration.ofMillis(60))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        __ -> log.info("First trace created event reported successfully"),
                        error -> log.error("Failed to report first trace created event", error));
    }

    private void sendBiEvent(String workspaceId, long traces) {
        if (usageReportService.isFirstTraceReport()) {
            return;
        }

        log.info("Reporting first trace created event for workspace '{}'", workspaceId);

        biEventService.reportEvent(
                usageReportService.getAnonymousId().orElseThrow(),
                Metadata.FIRST_TRACE_CREATED.getValue(),
                FIRST_TRACE_REPORT_BI_EVENT,
                Map.of(
                        "opik_app_version", config.getMetadata().getVersion(),
                        "traces_count", String.valueOf(traces),
                        "date", Instant.now().toString()));
    }

}
