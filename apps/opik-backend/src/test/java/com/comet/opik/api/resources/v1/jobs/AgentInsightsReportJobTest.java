package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsReportPublisher;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test of the daily sweep with mocked collaborators. Calls {@code runSweep} directly (bypassing the
 * distributed lock) to assert: jobs are grouped by workspace with a single trace query per workspace, only
 * jobs whose project had traces are enqueued, and one failing workspace doesn't abort the rest.
 */
@ExtendWith(MockitoExtension.class)
class AgentInsightsReportJobTest {

    private static final Instant PERIOD_START = Instant.parse("2026-06-13T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-06-14T00:00:00Z");

    @Mock
    private AgentInsightsJobService agentInsightsJobService;
    @Mock
    private TraceService traceService;
    @Mock
    private AgentInsightsReportPublisher reportPublisher;
    @Mock
    private LockService lockService;
    @Mock
    private OpikConfiguration config;

    private AgentInsightsReportJob job() {
        return new AgentInsightsReportJob(agentInsightsJobService, traceService, reportPublisher, lockService, config);
    }

    private static EnabledJob enabledJob(String workspaceId) {
        return EnabledJob.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .projectId(UUID.randomUUID())
                .build();
    }

    @Test
    @DisplayName("Enqueues only enabled jobs whose project had traces in the period")
    void runSweep__enqueuesOnlyJobsWithTraces() {
        String workspaceId = UUID.randomUUID().toString();
        var withTraces = enabledJob(workspaceId);
        var withoutTraces = enabledJob(workspaceId);
        when(agentInsightsJobService.findAllEnabled()).thenReturn(List.of(withTraces, withoutTraces));
        when(traceService.getProjectsWithTracesInRange(any(), any(), any()))
                .thenReturn(Mono.just(Set.of(withTraces.projectId())));
        when(reportPublisher.enqueue(any(), any(), any(), any(), any())).thenReturn(Mono.just("report-id"));

        job().runSweep(PERIOD_START, PERIOD_END).block();

        verify(reportPublisher).enqueue(withTraces.projectId(), workspaceId, PERIOD_START, PERIOD_END, "scheduled");
        verify(reportPublisher, never()).enqueue(eq(withoutTraces.projectId()), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Runs a single trace query for all enabled jobs across workspaces (no per-workspace loop)")
    void runSweep__singleTraceQueryForAllJobs() {
        String workspaceA = UUID.randomUUID().toString();
        String workspaceB = UUID.randomUUID().toString();
        var jobA = enabledJob(workspaceA);
        var jobB = enabledJob(workspaceB);
        when(agentInsightsJobService.findAllEnabled()).thenReturn(List.of(jobA, jobB));
        when(traceService.getProjectsWithTracesInRange(any(), any(), any()))
                .thenReturn(Mono.just(Set.of(jobA.projectId(), jobB.projectId())));
        when(reportPublisher.enqueue(any(), any(), any(), any(), any())).thenReturn(Mono.just("report-id"));

        job().runSweep(PERIOD_START, PERIOD_END).block();

        // Exactly one trace query for the whole enabled set (no per-workspace loop).
        verify(traceService).getProjectsWithTracesInRange(any(), eq(PERIOD_START), eq(PERIOD_END));
        verify(reportPublisher).enqueue(jobA.projectId(), workspaceA, PERIOD_START, PERIOD_END, "scheduled");
        verify(reportPublisher).enqueue(jobB.projectId(), workspaceB, PERIOD_START, PERIOD_END, "scheduled");
    }

    @Test
    @DisplayName("A failed enqueue does not skip the remaining jobs")
    void runSweep__perJobEnqueueFailureIsolated() {
        String workspaceId = UUID.randomUUID().toString();
        var jobA = enabledJob(workspaceId);
        var jobB = enabledJob(workspaceId);
        when(agentInsightsJobService.findAllEnabled()).thenReturn(List.of(jobA, jobB));
        when(traceService.getProjectsWithTracesInRange(any(), any(), any()))
                .thenReturn(Mono.just(Set.of(jobA.projectId(), jobB.projectId())));
        when(reportPublisher.enqueue(eq(jobA.projectId()), any(), any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        when(reportPublisher.enqueue(eq(jobB.projectId()), any(), any(), any(), any()))
                .thenReturn(Mono.just("report-id"));

        job().runSweep(PERIOD_START, PERIOD_END).block();

        verify(reportPublisher).enqueue(jobB.projectId(), workspaceId, PERIOD_START, PERIOD_END, "scheduled");
    }
}
