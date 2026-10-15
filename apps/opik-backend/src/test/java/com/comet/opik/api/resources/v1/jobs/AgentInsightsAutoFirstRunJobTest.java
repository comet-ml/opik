package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.domain.AgentInsightsJobService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test of the auto-first-run sweep with mocked collaborators. Calls {@code runSweep} directly (bypassing
 * the distributed lock) to assert: candidates come from the enrolled set, only those past the trace threshold
 * are run, the per-run cap is honoured, and one failing project doesn't abort the rest.
 */
@ExtendWith(MockitoExtension.class)
class AgentInsightsAutoFirstRunJobTest {

    private static final Instant PERIOD_END = Instant.parse("2026-06-14T00:00:00Z");
    private static final int NO_CAP = 100;

    @Mock
    private AgentInsightsJobService agentInsightsJobService;
    @Mock
    private TraceService traceService;
    @Mock
    private LockService lockService;
    @Mock
    private OpikConfiguration config;

    private AgentInsightsAutoFirstRunJob job() {
        return new AgentInsightsAutoFirstRunJob(agentInsightsJobService, traceService, lockService, config);
    }

    private static EnabledJob enrolled(String workspaceId) {
        return EnabledJob.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .projectId(UUID.randomUUID())
                .build();
    }

    @Test
    @DisplayName("Runs only the enrolled projects that are past the trace threshold")
    void runSweep__runsOnlyProjectsOverTheThreshold() {
        String workspaceId = UUID.randomUUID().toString();
        var overThreshold = enrolled(workspaceId);
        var belowThreshold = enrolled(workspaceId);
        when(agentInsightsJobService.findAwaitingFirstRun())
                .thenReturn(List.of(overThreshold, belowThreshold));
        when(traceService.getProjectsWithMinTracesInRange(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(Set.of(overThreshold.projectId())));

        job().runSweep(PERIOD_END, NO_CAP).block();

        verify(agentInsightsJobService).autoFirstRun(eq(workspaceId), eq(overThreshold.projectId()), any(),
                eq(PERIOD_END));
        verify(agentInsightsJobService, never()).autoFirstRun(any(), eq(belowThreshold.projectId()), any(), any());
    }

    @Test
    @DisplayName("Counts no traces when nothing is enrolled")
    void runSweep__skipsTheTraceQueryWhenNothingIsEnrolled() {
        when(agentInsightsJobService.findAwaitingFirstRun()).thenReturn(List.of());

        job().runSweep(PERIOD_END, NO_CAP).block();

        verify(traceService, never()).getProjectsWithMinTracesInRange(any(), any(), any(), anyInt());
        verify(agentInsightsJobService, never()).autoFirstRun(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Runs at most maxPerRun projects, so a large enrolment is spread over runs")
    void runSweep__honoursThePerRunCap() {
        String workspaceId = UUID.randomUUID().toString();
        var enrolledJobs = List.of(enrolled(workspaceId), enrolled(workspaceId), enrolled(workspaceId),
                enrolled(workspaceId), enrolled(workspaceId));
        when(agentInsightsJobService.findAwaitingFirstRun()).thenReturn(enrolledJobs);
        when(traceService.getProjectsWithMinTracesInRange(any(), any(), any(), anyInt())).thenReturn(Mono.just(
                enrolledJobs.stream().map(EnabledJob::projectId).collect(java.util.stream.Collectors.toSet())));

        job().runSweep(PERIOD_END, 2).block();

        verify(agentInsightsJobService, times(2)).autoFirstRun(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Counts traces over the job's own window and threshold")
    void runSweep__usesOwnWindowAndThreshold() {
        when(agentInsightsJobService.findAwaitingFirstRun())
                .thenReturn(List.of(enrolled(UUID.randomUUID().toString())));
        when(traceService.getProjectsWithMinTracesInRange(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(Set.of()));

        job().runSweep(PERIOD_END, NO_CAP).block();

        Instant expectedStart = PERIOD_END.minus(AgentInsightsAutoFirstRunJob.WINDOW);
        verify(traceService).getProjectsWithMinTracesInRange(any(), eq(expectedStart), eq(PERIOD_END),
                eq(AgentInsightsAutoFirstRunJob.MIN_TRACES));
    }

    @Test
    @DisplayName("A failed run does not skip the remaining projects")
    void runSweep__perProjectFailureIsolated() {
        String workspaceId = UUID.randomUUID().toString();
        var failing = enrolled(workspaceId);
        var succeeding = enrolled(workspaceId);
        when(agentInsightsJobService.findAwaitingFirstRun()).thenReturn(List.of(failing, succeeding));
        when(traceService.getProjectsWithMinTracesInRange(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(Set.of(failing.projectId(), succeeding.projectId())));
        doThrow(new RuntimeException("mysql down")).when(agentInsightsJobService)
                .autoFirstRun(any(), eq(failing.projectId()), any(), any());

        job().runSweep(PERIOD_END, NO_CAP).block();

        verify(agentInsightsJobService).autoFirstRun(eq(workspaceId), eq(succeeding.projectId()), any(),
                eq(PERIOD_END));
    }
}
