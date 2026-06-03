package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TraceToSummarize;
import com.comet.opik.domain.TraceSummaryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.TraceSummaryConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceSummarizationSubscriberTest {

    private static final String WORKSPACE_ID = "workspace-" + UUID.randomUUID();
    private static final String USER = "user-" + UUID.randomUUID();

    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;

    @Mock
    private RedissonReactiveClient redisson;

    @Mock
    private TraceSummaryService traceSummaryService;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private TraceSummarizationSubscriber subscriber;

    @BeforeEach
    void setUp() {
        var config = TraceSummaryConfig.builder()
                .streamName("trace-summary-test")
                .consumerGroupName("trace-summary-consumers-test")
                .build();
        subscriber = new TraceSummarizationSubscriber(config, serviceTogglesConfig, redisson, traceSummaryService);
    }

    private TraceToSummarize message() {
        var trace = podamFactory.manufacturePojo(Trace.class);
        return TraceToSummarize.builder().trace(trace).workspaceId(WORKSPACE_ID).userName(USER).build();
    }

    @Test
    @DisplayName("processEvent delegates to the summary service for the message trace and workspace")
    void processEvent__delegatesToSummaryService() {
        var message = message();
        when(traceSummaryService.summarize(eq(message.trace()), eq(WORKSPACE_ID))).thenReturn(Mono.empty());

        subscriber.processEvent(message).block();

        verify(traceSummaryService).summarize(message.trace(), WORKSPACE_ID);
    }

    @Test
    @DisplayName("processEvent propagates errors so the message can be retried")
    void processEvent__propagatesErrors() {
        var message = message();
        when(traceSummaryService.summarize(any(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("summarization failed")));

        assertThatThrownBy(() -> subscriber.processEvent(message).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("summarization failed");
    }

    @Test
    @DisplayName("when the toggle is disabled, then the consumer does not start (no Redis interaction)")
    void start__whenDisabled__thenDoesNotStart() {
        when(serviceTogglesConfig.isTraceSummarizationEnabled()).thenReturn(false);

        subscriber.start();

        verifyNoInteractions(redisson);
    }
}
