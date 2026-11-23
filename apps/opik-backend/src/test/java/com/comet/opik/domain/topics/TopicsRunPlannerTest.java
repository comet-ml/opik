package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
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
    private final String otherWorkspaceId = UUID.randomUUID().toString();
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

    @Test
    @DisplayName("with no first-build list, the plan looks for first builds in every workspace that runs topics")
    void planFindsFirstBuildsInEveryWorkspaceWithoutAList() {
        var enabled = List.of(workspaceId, otherWorkspaceId);

        verify(planWithFirstBuildList("", enabled)).find(eq(enabled), any(), any());
    }

    @Test
    @DisplayName("with a first-build list, the plan looks for first builds only in the listed workspaces")
    void planFindsFirstBuildsOnlyInListedWorkspaces() {
        var candidates = planWithFirstBuildList(" " + workspaceId + " ,", List.of(workspaceId, otherWorkspaceId));

        verify(candidates).find(eq(List.of(workspaceId)), any(), any());
    }

    @Test
    @DisplayName("a listed workspace that does not run topics gets no first build")
    void planIgnoresAListedWorkspaceThatDoesNotRunTopics() {
        var notEnabled = UUID.randomUUID().toString();
        var candidates = planWithFirstBuildList(notEnabled + "," + otherWorkspaceId, List.of(workspaceId,
                otherWorkspaceId));

        verify(candidates).find(eq(List.of(otherWorkspaceId)), any(), any());
    }

    /** Runs one plan with {@code topics.firstBuild.workspaces} set to {@code listed}; returns its candidate finder. */
    private TopicsFirstBuildCandidates planWithFirstBuildList(String listed, List<String> enabled) {
        var config = TestConfigUtils.loadConfigTest().getTopics();
        config.getFirstBuild().setWorkspaces(listed);
        var template = mock(TransactionTemplate.class);
        var workspaces = mock(TopicWorkspaceService.class);
        var reconciler = mock(TopicsRunReconciler.class);
        var candidates = mock(TopicsFirstBuildCandidates.class);
        when(workspaces.enabledWorkspaceIds()).thenReturn(enabled);
        when(store.findInFlight()).thenReturn(Mono.just(List.of()));
        when(reconciler.reconcile(any(), any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(template.inTransaction(eq(READ_ONLY), any())).thenReturn(List.of());
        when(candidates.find(any(), any(), any())).thenReturn(Mono.just(List.of()));
        new TopicsRunPlanner(config, template, workspaces, store, reconciler, candidates,
                mock(TopicsRunPublisher.class), new TopicsBackfillGate(config)).plan(Instant.now()).block();
        return candidates;
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
                mock(TopicsRunReconciler.class), candidates, publisher, new TopicsBackfillGate(CONFIG));
    }
}
