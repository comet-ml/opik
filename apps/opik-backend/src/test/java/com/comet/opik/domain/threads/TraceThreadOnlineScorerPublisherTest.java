package com.comet.opik.domain.threads;

import com.comet.opik.api.resources.v1.events.OnlineScoringQuotaService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.auth.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TraceThreadOnlineScorerPublisher per-workspace quota integration")
class TraceThreadOnlineScorerPublisherTest {

    @Mock
    private OnlineScorePublisher onlineScorePublisher;
    @Mock
    private OnlineScoringQuotaService quotaService;

    private TraceThreadOnlineScorerPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TraceThreadOnlineScorerPublisher(onlineScorePublisher, quotaService);
    }

    private void publish(UUID projectId, List<TraceThreadModel> threads) {
        publisher.publish(projectId, threads)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, "ws-1")
                        .put(RequestContext.USER_NAME, "user-1"))
                .block();
    }

    @Test
    void doesNotEnqueueWhenQuotaDropsAll() {
        var projectId = UUID.randomUUID();
        var ruleId = UUID.randomUUID();
        var thread = TraceThreadModel.builder().threadId("t1").sampling(Map.of(ruleId, true)).build();
        when(quotaService.admit(any(), any(), any(), any())).thenReturn(List.of());

        publish(projectId, List.of(thread));

        verifyNoInteractions(onlineScorePublisher);
    }

    @Test
    void enqueuesOnlyTheQuotaAdmittedThreadIds() {
        var projectId = UUID.randomUUID();
        var ruleId = UUID.randomUUID();
        var thread1 = TraceThreadModel.builder().threadId("t1").sampling(Map.of(ruleId, true)).build();
        var thread2 = TraceThreadModel.builder().threadId("t2").sampling(Map.of(ruleId, true)).build();
        // Quota admits only one of the two sampled threads.
        when(quotaService.admit(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(3)).subList(0, 1));

        publish(projectId, List.of(thread1, thread2));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(onlineScorePublisher).enqueueThreadMessage(
                captor.capture(), eq(ruleId), eq(projectId), eq("ws-1"), eq("user-1"));
        assertThat(captor.getValue()).hasSize(1);
    }
}
