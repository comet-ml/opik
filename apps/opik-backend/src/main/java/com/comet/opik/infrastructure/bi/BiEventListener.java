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
import java.util.concurrent.ConcurrentHashMap;
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
    private final AnalyticsService analyticsService;

    /**
     * Best-effort, in-memory dedup for per-workspace first_trace_created analytics events.
     *
     * <p>Known limitations:
     * <ul>
     *   <li>Grows monotonically — entries are never evicted, leading to unbounded memory usage.
     *       Each entry is ~116 bytes (36-char UUID string + ConcurrentHashMap.Node overhead),
     *       so 1M workspaces ≈ 116 MB.</li>
     *   <li>Lost on JVM restarts — workspaces will be re-reported after a redeployment.</li>
     *   <li>Not shared across replicas — each instance tracks independently, so multi-replica
     *       deployments will emit duplicates.</li>
     * </ul>
     *
     * TODO: replace with a bounded or persistent dedup mechanism in a follow-up PR.
     */
    private static final Set<String> ANALYTICS_REPORTED_WORKSPACES = ConcurrentHashMap.newKeySet();

    @Inject
    public BiEventListener(@NonNull ProjectService projectService,
            @NonNull UsageReportService usageReportService, @NonNull TraceService traceService,
            @NonNull OpikConfiguration config, @NonNull LockService lockService,
            @NonNull BiEventService biEventService, @NonNull AnalyticsService analyticsService) {
        this.projectService = projectService;
        this.traceService = traceService;
        this.config = config;
        this.usageReportService = usageReportService;
        this.lockService = lockService;
        this.biEventService = biEventService;
        this.analyticsService = analyticsService;
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        if (!config.getUsageReport().isEnabled()) {
            return;
        }

        if (event.traces().isEmpty()) {
            log.warn("No trace ids found for event '{}'", event);
            return;
        }

        var projectIds = getNonDemoProjectIds(event.workspaceId(), event);
        if (projectIds.isEmpty()) {
            log.info("No project ids found for event");
            return;
        }

        checkIfItIsFirstTraceAndReport(event.workspaceId(), event, projectIds);

        trackFirstTraceViaAnalytics(event.workspaceId(), event);
    }

    private Set<UUID> getNonDemoProjectIds(String workspaceId, TracesCreated event) {
        Set<UUID> demoProjectIds = projectService.findByNames(workspaceId, DemoData.PROJECTS)
                .stream()
                .map(Project::id)
                .collect(Collectors.toSet());

        Set<UUID> projectIds = new HashSet<>(event.projectIds());
        projectIds.removeAll(demoProjectIds);
        return projectIds;
    }

    private void checkIfItIsFirstTraceAndReport(String workspaceId, TracesCreated event, Set<UUID> projectIds) {
        if (usageReportService.isFirstTraceReport()) {
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

    private void trackFirstTraceViaAnalytics(String workspaceId, TracesCreated event) {
        if (!config.getAnalytics().isEnabled()) {
            return;
        }

        if (!ANALYTICS_REPORTED_WORKSPACES.add(workspaceId)) {
            return;
        }

        analyticsService.trackEvent("first_trace_created", Map.of(
                "workspace_id", workspaceId,
                "user_name", event.userName(),
                "date", Instant.now().toString()), event.userName());
    }
}
