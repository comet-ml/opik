package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.domain.topics.TopicProject.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics run subscriber")
class TopicsRunSubscriberTest {

    private final TopicRunStore store = mock(TopicRunStore.class);
    private final TopicsRunPlanner planner = mock(TopicsRunPlanner.class);
    private final TopicsRunSubscriber subscriber = new TopicsRunSubscriber(
            TestConfigUtils.loadConfigTest().getTopics(), mock(RedissonReactiveClient.class), store,
            mock(TopicsRebuildService.class), mock(TopicWindowLabelService.class), planner);
    private final TopicsRunMessage message = TopicsRunMessage.builder().runId(UUID.randomUUID())
            .workspaceId("workspace").projectId(UUID.randomUUID()).mode(RunMode.FIRST_BUILD).build();
    private final Mono<TopicsRebuildService.Outcome> saved = Mono.just(TopicsRebuildService.Outcome.builder()
            .runId(message.runId()).status(RunStatus.SAVED).build());

    @Test
    @DisplayName("a list saved after its project was deleted gets no labelling pass")
    void deletedProjectGetsNoLabels() {
        when(store.projectExists(message.workspaceId(), message.projectId())).thenReturn(Mono.just(false));

        subscriber.labelsAfter(message, saved).block();

        verify(planner, never()).queueOne(any());
    }

    @Test
    @DisplayName("when the project check fails, the labelling pass is queued and the planner settles it")
    void failedProjectCheckQueuesLabels() {
        when(store.projectExists(message.workspaceId(), message.projectId()))
                .thenReturn(Mono.error(new IllegalStateException("mysql is down")));
        when(planner.queueOne(any())).thenReturn(Mono.just(UUID.randomUUID()));

        subscriber.labelsAfter(message, saved).block();

        verify(planner).queueOne(TopicsRunPlanner.Planned.builder().workspaceId(message.workspaceId())
                .projectId(message.projectId()).mode(RunMode.LABELS).build());
    }
}
