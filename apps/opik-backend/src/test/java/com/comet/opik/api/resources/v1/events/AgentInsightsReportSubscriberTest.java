package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.domain.AgentInsightsReportMessage;
import com.comet.opik.domain.AgentInsightsTriggerException;
import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers the seam between the trigger client and the run-failure record: which reason the subscriber
 * persists for a failed trigger. The client names the reason and the subscriber records it, so a
 * regression here is invisible to both {@code PlatformAgentInsightsReportClientTest} (which stops at the
 * exception) and {@code AgentInsightsJobsResourceTest} (which starts at the stored row).
 *
 * <p>These also pin the assumption the mapping rests on: {@code Mono.fromRunnable} delivers the exception to
 * {@code onErrorResume} unwrapped, so the {@code instanceof} still matches.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Insights Report Subscriber")
class AgentInsightsReportSubscriberTest {

    private static final String WORKSPACE_ID = "workspace-id";
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Mock
    private AgentInsightsReportConfig config;
    @Mock
    private ServiceTogglesConfig serviceToggles;
    @Mock
    private RedissonReactiveClient redisson;
    @Mock
    private AgentInsightsReportClient reportClient;
    @Mock
    private AgentInsightsJobService jobService;

    private AgentInsightsReportSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new AgentInsightsReportSubscriber(config, serviceToggles, redisson, reportClient, jobService);
    }

    private static AgentInsightsReportMessage message() {
        Instant periodEnd = Instant.now();
        return new AgentInsightsReportMessage("report-1", PROJECT_ID, WORKSPACE_ID,
                periodEnd.minusSeconds(86_400), periodEnd, "manual");
    }

    private void failTriggerWith(RuntimeException failure) {
        doThrow(failure).when(reportClient)
                .triggerAgentInsights(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A credit rejection is recorded as out of credits, not as a run that never started")
    void processEvent__triggerRejectedForCredits__recordsOutOfCredits() {
        failTriggerWith(new AgentInsightsTriggerException(AgentInsightsJob.FailureReason.OUT_OF_CREDITS,
                "insufficient credits"));

        StepVerifier.create(subscriber.processEvent(message())).verifyComplete();

        verify(jobService).markRunFailed(eq(WORKSPACE_ID), eq(PROJECT_ID),
                eq(AgentInsightsJob.FailureReason.OUT_OF_CREDITS), eq("insufficient credits"));
    }

    @Test
    @DisplayName("A transport failure the client never classified is recorded as did not start")
    void processEvent__triggerThrowsUnclassified__recordsDidNotStart() {
        // Not an AgentInsightsTriggerException: the call never reached the platform, so no reason was named.
        failTriggerWith(new IllegalStateException("connection refused"));

        StepVerifier.create(subscriber.processEvent(message())).verifyComplete();

        verify(jobService).markRunFailed(eq(WORKSPACE_ID), eq(PROJECT_ID),
                eq(AgentInsightsJob.FailureReason.DID_NOT_START), eq("connection refused"));
    }

    @Test
    @DisplayName("An accepted trigger records no failure")
    void processEvent__triggerAccepted__recordsNoFailure() {
        StepVerifier.create(subscriber.processEvent(message())).verifyComplete();

        verify(jobService, never()).markRunFailed(any(), any(), any(), any());
    }
}
