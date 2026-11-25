package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.topics.TopicProject.CancelReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("topics run reconciler")
class TopicsRunReconcilerTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String WORKSPACE_ID = ID_GENERATOR.generateId().toString();

    @Test
    @DisplayName("a stale queued nightly update on a day the schedule did not fire is dropped, not published again")
    void staleNightlyUpdateWithNoCurrentNightIsDropped() {
        var config = TestConfigUtils.loadConfigTest().getTopics();
        // Mondays only; the 18th is a Wednesday, so neither it nor the day before is a night.
        config.getNightly().setSchedule("0 0 2 ? * MON");
        var now = Instant.parse("2026-11-18T12:00:00Z");
        var store = mock(TopicRunStore.class);
        var publisher = mock(TopicsRunPublisher.class);
        var candidates = mock(TopicsFirstBuildCandidates.class);
        var run = InFlightRun.builder().workspaceId(WORKSPACE_ID).projectId(ID_GENERATOR.generateId())
                .runId(ID_GENERATOR.generateId()).runMode(TopicProject.RunMode.NIGHTLY_UPDATE)
                .runStatus(TopicProject.RunStatus.QUEUED).runQueuedAt(now.minus(1, ChronoUnit.DAYS)).build();
        when(store.dropQueued(any(), any(), any(), any())).thenReturn(Mono.just(true));

        var inFlight = new TopicsRunReconciler(config, store, publisher, candidates)
                .reconcile(List.of(run), List.of(WORKSPACE_ID), LocalDate.parse("2026-11-18"), now)
                .block();

        assertThat(inFlight).isEmpty();
        verify(store).dropQueued(run.workspaceId(), run.projectId(), run.runId(), CancelReason.DEADLINE);
        verifyNoInteractions(publisher, candidates);
    }
}
