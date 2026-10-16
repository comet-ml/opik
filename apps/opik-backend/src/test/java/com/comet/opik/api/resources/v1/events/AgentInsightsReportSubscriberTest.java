package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsMetrics;
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.domain.AgentInsightsReportMessage;
import com.comet.opik.domain.AgentInsightsRunGuidanceService;
import com.comet.opik.domain.AgentInsightsTriggerException;
import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock
    private AgentInsightsRunGuidanceService runGuidanceService;

    private AgentInsightsReportSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new AgentInsightsReportSubscriber(config, serviceToggles, redisson, reportClient, jobService,
                runGuidanceService);
    }

    private static AgentInsightsReportMessage message() {
        return message(AgentInsightsMetrics.MANUAL);
    }

    private static AgentInsightsReportMessage message(String triggerSource) {
        Instant periodEnd = Instant.now();
        return new AgentInsightsReportMessage("report-1", PROJECT_ID, WORKSPACE_ID,
                periodEnd.minusSeconds(86_400), periodEnd, triggerSource, null, null);
    }

    private void failTriggerWith(RuntimeException failure) {
        doThrow(failure).when(reportClient)
                .triggerAgentInsights(any());
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
    @DisplayName("Comet's free budget running out on the automatic first run cancels the rollout")
    void processEvent__freePoolExhaustedOnAutoFirstRun__cancelsRollout() {
        failTriggerWith(new AgentInsightsTriggerException(AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED,
                "free budget unavailable"));

        StepVerifier.create(subscriber.processEvent(message(AgentInsightsMetrics.AUTO_FIRST_RUN))).verifyComplete();

        verify(jobService).cancelAutoFirstRunRollout(WORKSPACE_ID, PROJECT_ID);
        verify(jobService, never()).markRunFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Free-budget exhaustion reported on any other run is recorded as out of credits, never cancelling the rollout")
    void processEvent__freePoolExhaustedOnManualRun__recordsOutOfCreditsInstead() {
        // Only the free run draws on Comet's budget, so this is a misclassification; cancelling the rollout for
        // every workspace over it would leave nothing to turn it back on.
        failTriggerWith(new AgentInsightsTriggerException(AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED,
                "free budget unavailable"));

        StepVerifier.create(subscriber.processEvent(message(AgentInsightsMetrics.MANUAL))).verifyComplete();

        verify(jobService, never()).cancelAutoFirstRunRollout(any(), any());
        verify(jobService).markRunFailed(eq(WORKSPACE_ID), eq(PROJECT_ID),
                eq(AgentInsightsJob.FailureReason.OUT_OF_CREDITS), eq("free budget unavailable"));
    }

    @Test
    @DisplayName("An accepted trigger records no failure")
    void processEvent__triggerAccepted__recordsNoFailure() {
        StepVerifier.create(subscriber.processEvent(message())).verifyComplete();

        verify(jobService, never()).markRunFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("The guidance read at enqueue is passed on to the trigger")
    void processEvent__messageWithGuidance__triggersWithIt() {
        var message = message().toBuilder().guidance("Only report billing failures").guidanceVersion(3).build();

        when(serviceToggles.isAgentInsightsGuidanceActive()).thenReturn(true);

        StepVerifier.create(subscriber.processEvent(message)).verifyComplete();

        verify(reportClient).triggerAgentInsights(expectedTrigger(message, "Only report billing failures"));
        verify(runGuidanceService, never()).replaceIfStamped(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Guidance turned off after enqueue: the queued run is sent without it")
    void processEvent__guidanceTurnedOffAfterEnqueue__triggersWithoutIt() {
        var message = message().toBuilder().guidance("Only report billing failures").guidanceVersion(3).build();
        when(serviceToggles.isAgentInsightsGuidanceActive()).thenReturn(false);

        StepVerifier.create(subscriber.processEvent(message)).verifyComplete();

        verify(reportClient).triggerAgentInsights(expectedTrigger(message, null));
        // The run was stamped with version 3 at enqueue; its results now reflect no guidance.
        verify(runGuidanceService).replaceIfStamped(WORKSPACE_ID, PROJECT_ID, 3, 0);
    }

    @Test
    @DisplayName("Guidance off and the run carried no version: the stamp is left alone")
    void processEvent__guidanceOffNoVersion__leavesStampAlone() {
        when(serviceToggles.isAgentInsightsGuidanceActive()).thenReturn(false);

        StepVerifier.create(subscriber.processEvent(message())).verifyComplete();

        verify(runGuidanceService, never()).replaceIfStamped(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("A failed trigger clears the run's guidance stamp: the run never got that guidance")
    void processEvent__triggerFailsWithGuidanceVersion__clearsStamp() {
        var message = message().toBuilder().guidance("Only report billing failures").guidanceVersion(3).build();
        when(serviceToggles.isAgentInsightsGuidanceActive()).thenReturn(true);
        failTriggerWith(new IllegalStateException("connection refused"));

        StepVerifier.create(subscriber.processEvent(message)).verifyComplete();

        verify(runGuidanceService).replaceIfStamped(WORKSPACE_ID, PROJECT_ID, 3, null);
    }

    @Test
    @DisplayName("Guidance turned off and the trigger then fails: the swapped stamp is the one cleared")
    void processEvent__guidanceOffThenTriggerFails__clearsSwappedStamp() {
        var message = message().toBuilder().guidance("Only report billing failures").guidanceVersion(3).build();
        when(serviceToggles.isAgentInsightsGuidanceActive()).thenReturn(false);
        failTriggerWith(new IllegalStateException("connection refused"));

        StepVerifier.create(subscriber.processEvent(message)).verifyComplete();

        InOrder order = inOrder(runGuidanceService);
        order.verify(runGuidanceService).replaceIfStamped(WORKSPACE_ID, PROJECT_ID, 3, 0);
        order.verify(runGuidanceService).replaceIfStamped(WORKSPACE_ID, PROJECT_ID, 0, null);
    }

    private static AgentInsightsReportClient.Trigger expectedTrigger(AgentInsightsReportMessage message,
            String guidance) {
        return AgentInsightsReportClient.Trigger.builder()
                .reportId(message.reportId())
                .projectId(PROJECT_ID)
                .workspaceId(WORKSPACE_ID)
                .periodStart(message.periodStart())
                .periodEnd(message.periodEnd())
                .triggerSource(AgentInsightsMetrics.MANUAL)
                .guidance(guidance)
                .build();
    }
}
