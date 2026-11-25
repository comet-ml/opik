package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics run planner")
class TopicsRunPlannerTest {

    private static final TopicsConfig CONFIG = TestConfigUtils.loadConfigTest().getTopics();

    private final String workspaceId = UUID.randomUUID().toString();
    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final LocalDate night = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    private final TopicRunStore store = mock(TopicRunStore.class);

    @Test
    @DisplayName("a run by hand that cannot be published is dropped, and the caller gets an error")
    void runByHandDropsAnUnpublishedRun() {
        var planner = plannerWithPublishError(true, false);

        assertThatThrownBy(() -> planner.runByHand(workspaceId, projectId, night, true).block())
                .isInstanceOf(ServiceUnavailableException.class);
        verify(store).dropUnclaimed(workspaceId, projectId, runId);
    }

    @Test
    @DisplayName("a run by hand that a pod claimed despite its publish error is reported as started")
    void runByHandReportsAClaimedRun() {
        var planner = plannerWithPublishError(false, true);

        assertThat(planner.runByHand(workspaceId, projectId, night, true).block()).isEqualTo(runId);
    }

    @Test
    @DisplayName("a run by hand cancelled while queued after its publish error gives the caller an error")
    void runByHandFailsForARunCancelledWhileQueued() {
        var planner = plannerWithPublishError(false, false);

        assertThatThrownBy(() -> planner.runByHand(workspaceId, projectId, night, true).block())
                .isInstanceOf(ServiceUnavailableException.class);
    }

    private TopicsRunPlanner plannerWithPublishError(boolean dropped, boolean claimed) {
        var workspaces = mock(TopicWorkspaceService.class);
        var candidates = mock(TopicsDiscoveryCandidates.class);
        var publisher = mock(TopicsRunPublisher.class);
        when(workspaces.isEnabled(workspaceId)).thenReturn(true);
        when(candidates.findListless(workspaceId, projectId, night)).thenReturn(Mono.just(Optional.of(
                ListlessProject.builder().workspaceId(workspaceId).projectId(projectId).build())));
        when(candidates.windowVectors(workspaceId, projectId, night))
                .thenReturn(Mono.just((long) CONFIG.getRun().getMinVectors()));
        when(store.queue(workspaceId, projectId, TopicProject.RunMode.DISCOVERY))
                .thenReturn(Mono.just(Optional.of(runId)));
        when(publisher.publish(any())).thenReturn(Mono.error(new IllegalStateException("redis is down")));
        when(store.dropUnclaimed(workspaceId, projectId, runId)).thenReturn(Mono.just(dropped));
        when(store.isClaimed(workspaceId, projectId, runId)).thenReturn(Mono.just(claimed));
        return new TopicsRunPlanner(CONFIG, mock(TransactionTemplate.class), workspaces, store,
                mock(TopicsRunReconciler.class), candidates, publisher, new TopicsBackfillGate(CONFIG));
    }
}
