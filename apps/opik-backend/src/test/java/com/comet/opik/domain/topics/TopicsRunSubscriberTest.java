package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.LogCaptureUtils;
import com.comet.opik.domain.topics.TopicProject.RunError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics run subscriber")
class TopicsRunSubscriberTest {

    @Test
    @DisplayName("an error thrown in a map of the run, under the stream's onErrorContinue, fails the row internal at "
            + "once and logs no error message")
    void errorInARunFailsItAtOnce() {
        var store = mock(TopicRunStore.class);
        var labelService = mock(TopicWindowLabelService.class);
        var subscriber = new TopicsRunSubscriber(TestConfigUtils.loadConfigTest().getTopics(),
                mock(RedissonReactiveClient.class), store, mock(TopicsRunService.class), labelService,
                mock(TopicsRunPlanner.class), mock(TopicsRunPublisher.class));
        var message = TopicsRunMessage.builder().runId(UUID.randomUUID()).workspaceId("workspace")
                .projectId(UUID.randomUUID()).mode(TopicProject.RunMode.LABELS).build();
        var workspace = new TopicsMetrics.Workspace("workspace", "name");
        var secret = "SECRET-" + UUID.randomUUID();
        when(store.claim(eq(message), any())).thenReturn(Mono.just(true));
        when(store.resolveWorkspace("workspace")).thenReturn(Mono.just(workspace));
        when(store.heartbeat(message)).thenReturn(Mono.just(true));
        when(store.endCancelled(any(), any(), any())).thenReturn(Mono.empty());
        when(store.fail(message.runId(), workspace, message.projectId(), RunError.INTERNAL))
                .thenReturn(Mono.just(true));
        when(labelService.labelWindow(eq(message.projectId()), any(), eq(LabelTrigger.RESUME)))
                .thenReturn(Mono.just(LabelPassOutcome.COMPLETE).map(outcome -> {
                    throw new IllegalStateException(secret);
                }));
        var dropped = new ArrayList<Object>();

        // As BaseRedisSubscriber runs it: in a flatMap, under onErrorContinue.
        var logs = LogCaptureUtils.logEventsOf(() -> Flux.just(message)
                .flatMap(subscriber::processEvent)
                .onErrorContinue((error, element) -> dropped.add(element))
                .blockLast());

        verify(store).fail(message.runId(), workspace, message.projectId(), RunError.INTERNAL);
        assertThat(dropped).isEmpty();
        assertThat(logs).noneMatch(line -> line.contains(secret));
        assertThat(logs).anyMatch(line -> line.startsWith("Topics run '%s' failed, project '%s', mode 'labels', "
                .formatted(message.runId(), message.projectId()) + "code 'internal', marked 'true', errors "
                + "'[java.lang.IllegalStateException]'"));
    }
}
