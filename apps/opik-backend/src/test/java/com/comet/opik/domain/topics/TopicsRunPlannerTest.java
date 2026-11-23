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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        var planner = plannerWithPublishError(true);

        assertThatThrownBy(() -> planner.runByHand(workspaceId, projectId, night, true).block())
                .isInstanceOf(ServiceUnavailableException.class);
        verify(store).dropQueued(eq(workspaceId), eq(projectId), eq(runId), isNull());
    }

    @Test
    @DisplayName("a run by hand that left the queue despite its publish error is reported as started")
    void runByHandReportsARunThatLeftTheQueue() {
        var planner = plannerWithPublishError(false);

        assertThat(planner.runByHand(workspaceId, projectId, night, true).block()).isEqualTo(runId);
    }

    private TopicsRunPlanner plannerWithPublishError(boolean dropped) {
        var workspaces = mock(TopicWorkspaceService.class);
        var candidates = mock(TopicsFirstBuildCandidates.class);
        var publisher = mock(TopicsRunPublisher.class);
        when(workspaces.isEnabled(workspaceId)).thenReturn(true);
        when(candidates.findListless(workspaceId, projectId, night)).thenReturn(Mono.just(Optional.of(
                ListlessProject.builder().workspaceId(workspaceId).projectId(projectId).build())));
        when(candidates.windowVectors(workspaceId, projectId, night))
                .thenReturn(Mono.just((long) CONFIG.getBuild().getMinVectors()));
        when(store.queue(workspaceId, projectId, TopicProject.RunMode.FIRST_BUILD))
                .thenReturn(Mono.just(Optional.of(runId)));
        when(publisher.publish(any())).thenReturn(Mono.error(new IllegalStateException("redis is down")));
        when(store.dropQueued(eq(workspaceId), eq(projectId), eq(runId), isNull())).thenReturn(Mono.just(dropped));
        return new TopicsRunPlanner(CONFIG, mock(TransactionTemplate.class), workspaces, store,
                mock(TopicsRunReconciler.class), candidates, publisher, new TopicsBackfillGate(CONFIG),
                mock(TopicsNightPass.class));
    }

    @Test
    @DisplayName("a nightly update by hand that cannot be published is dropped, and the caller gets an error")
    void nightByHandDropsAnUnpublishedRun() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var night = LocalDate.now(ZoneOffset.UTC);

        var store = mock(TopicRunStore.class);
        var publisher = mock(TopicsRunPublisher.class);
        var nightPass = mock(TopicsNightPass.class);
        when(nightPass.byHand(eq(workspaceId), eq(projectId), eq(night), any()))
                .thenAnswer(invocation -> invocation.<Function<TopicsRunPlanner.Planned, Mono<UUID>>>getArgument(3)
                        .apply(TopicsRunPlanner.Planned.builder().workspaceId(workspaceId).projectId(projectId)
                                .mode(TopicProject.RunMode.NIGHTLY_UPDATE).night(night).windowTraces(100).build()));
        when(store.queue(workspaceId, projectId, TopicProject.RunMode.NIGHTLY_UPDATE))
                .thenReturn(Mono.just(Optional.of(runId)));
        when(publisher.publish(any())).thenReturn(Mono.error(new IllegalStateException("redis is down")));
        when(store.dropQueued(eq(workspaceId), eq(projectId), eq(runId), isNull())).thenReturn(Mono.just(true));
        var planner = new TopicsRunPlanner(CONFIG, mock(TransactionTemplate.class), mock(TopicWorkspaceService.class),
                store, mock(TopicsRunReconciler.class), mock(TopicsFirstBuildCandidates.class), publisher,
                new TopicsBackfillGate(CONFIG), nightPass);

        assertThatThrownBy(() -> planner.nightByHand(workspaceId, projectId, night).block())
                .isInstanceOf(ServiceUnavailableException.class);
        verify(store).dropQueued(eq(workspaceId), eq(projectId), eq(runId), isNull());
    }
}
