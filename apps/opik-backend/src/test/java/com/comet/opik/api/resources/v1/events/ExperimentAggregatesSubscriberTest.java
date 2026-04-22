package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ExperimentAggregationMessage;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentAggregatesSubscriberTest {

    @Mock
    private RedissonReactiveClient redisson;

    @Mock
    private ExperimentAggregatesService experimentAggregatesService;

    @Mock
    private ExperimentAggregationPublisher publisher;

    @Mock
    private LockService lockService;

    @Mock
    private RAtomicLongReactive atomicLong;

    private ExperimentDenormalizationConfig config;
    private ExperimentAggregatesSubscriber subscriber;

    @BeforeEach
    void setUp() {
        config = buildConfig(true);
        subscriber = new ExperimentAggregatesSubscriber(config, redisson, experimentAggregatesService, publisher,
                lockService);
        lenient().when(redisson.getAtomicLong(any(String.class))).thenReturn(atomicLong);
        lenient().when(atomicLong.delete()).thenReturn(Mono.just(true));
    }

    @Nested
    class Lifecycle {

        @Test
        void startSkipsStartupWhenDisabled() {
            config = buildConfig(false);
            subscriber = new ExperimentAggregatesSubscriber(config, redisson, experimentAggregatesService, publisher,
                    lockService);

            subscriber.start();

            verify(redisson, never()).getStream(any(), any());
        }

        @Test
        void stopSkipsShutdownWhenDisabled() {
            config = buildConfig(false);
            subscriber = new ExperimentAggregatesSubscriber(config, redisson, experimentAggregatesService, publisher,
                    lockService);

            subscriber.stop();

            verify(redisson, never()).getStream(any(), any());
        }
    }

    @Nested
    class ProcessEvent {

        @Test
        void processEventShouldAcquireWorkspaceScopedLockAndCallService() {
            var experimentId = UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            var message = ExperimentAggregationMessage.builder()
                    .experimentId(experimentId)
                    .workspaceId(workspaceId)
                    .userName("system")
                    .build();

            when(lockService.bestEffortLock(any(Lock.class), any(), any(), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(experimentAggregatesService.populateAggregations(experimentId))
                    .thenReturn(Mono.empty());

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyComplete();

            var lockCaptor = ArgumentCaptor.forClass(Lock.class);
            verify(lockService).bestEffortLock(lockCaptor.capture(), any(), any(), any(), any());
            assertThat(lockCaptor.getValue().key())
                    .contains(workspaceId)
                    .contains(experimentId.toString());
            verify(experimentAggregatesService).populateAggregations(experimentId);
        }

        @Test
        void processEventShouldPropagateErrorWhenServiceFails() {
            var experimentId = UUID.randomUUID();
            var message = ExperimentAggregationMessage.builder()
                    .experimentId(experimentId)
                    .workspaceId(UUID.randomUUID().toString())
                    .userName("system")
                    .build();

            var expectedError = new RuntimeException("aggregation failed");

            when(lockService.bestEffortLock(any(Lock.class), any(), any(), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(experimentAggregatesService.populateAggregations(experimentId))
                    .thenReturn(Mono.error(expectedError));

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyErrorMatches(e -> e == expectedError);
        }

        @Test
        void processEventShouldCompleteWithoutRetriggerWhenLockNotAcquired() {
            var experimentId = UUID.randomUUID();
            var message = ExperimentAggregationMessage.builder()
                    .experimentId(experimentId)
                    .workspaceId(UUID.randomUUID().toString())
                    .userName("system")
                    .build();

            // bestEffortLock returns empty when lock is held by another node
            when(lockService.bestEffortLock(any(Lock.class), any(), any(), any(), any()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyComplete();

            verify(publisher, never()).publish(any(), any(), any());
            verify(experimentAggregatesService, never()).populateAggregations(any());
            verify(atomicLong, never()).delete();
        }

        @Test
        void processEventShouldNotResetRetryCounterWhenLockNotAcquired() {
            var experimentId = UUID.randomUUID();
            var message = ExperimentAggregationMessage.builder()
                    .experimentId(experimentId)
                    .workspaceId(UUID.randomUUID().toString())
                    .userName("system")
                    .build();

            // Simulate lock already held by another node: bestEffortLock runs the skip Mono (Mono.empty)
            when(lockService.bestEffortLock(any(Lock.class), any(), any(), any(), any()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyComplete();

            // resetRetryCounter must NOT run when the lock was not acquired — otherwise
            // this node would wipe a counter that belongs to the node currently holding the lock
            verify(atomicLong, never()).delete();
        }

        @Test
        void processEventShouldCancelAndRetriggerViaDebounceWhenActionExceedsLockTtl() {
            var experimentId = UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            var message = ExperimentAggregationMessage.builder()
                    .experimentId(experimentId)
                    .workspaceId(workspaceId)
                    .userName("system")
                    .build();

            // Lock is acquired; bestEffortLock executes the action directly.
            // The action never completes, so the .timeout() inside processEvent fires and cancels it.
            when(lockService.bestEffortLock(any(Lock.class), any(), any(), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(experimentAggregatesService.populateAggregations(experimentId))
                    .thenReturn(Mono.never()); // hangs forever — timeout fires after lock TTL (1s in test config)
            when(atomicLong.incrementAndGet()).thenReturn(Mono.just(1L));
            when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
            when(publisher.publish(any(), any(), any())).thenReturn(Mono.empty());

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyComplete();

            verify(publisher).publish(any(), any(), any());
            verify(atomicLong, never()).delete();
        }

        @Test
        void processEventShouldStopRetriggerAfterMaxLockExpiryRetries() {
            var experimentId = UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            var message = ExperimentAggregationMessage.builder()
                    .experimentId(experimentId)
                    .workspaceId(workspaceId)
                    .userName("system")
                    .build();

            // Lock is acquired; action never completes so the timeout fires.
            // Counter is already at max retries — should stop re-triggering.
            when(lockService.bestEffortLock(any(Lock.class), any(), any(), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(experimentAggregatesService.populateAggregations(experimentId))
                    .thenReturn(Mono.never());
            when(atomicLong.incrementAndGet()).thenReturn(Mono.just((long) config.getMaxLockExpiryRetries() + 1));

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyComplete();

            verify(publisher, never()).publish(any(), any(), any());
            verify(atomicLong).delete();
        }
    }

    private static ExperimentDenormalizationConfig buildConfig(boolean enabled) {
        var config = new ExperimentDenormalizationConfig();
        config.setEnabled(enabled);
        config.setStreamName("experiment_denormalization_stream");
        config.setConsumerGroupName("experiment_denormalization");
        config.setConsumerBatchSize(100);
        config.setPoolingInterval(Duration.milliseconds(500));
        config.setLongPollingDuration(Duration.seconds(5));
        config.setDebounceDelay(Duration.seconds(1));
        config.setJobLockTime(Duration.seconds(4));
        config.setJobLockWaitTime(Duration.milliseconds(300));
        config.setAggregationLockTime(Duration.seconds(1)); // Short TTL to trigger expiry detection in tests
        config.setLockAcquireWait(Duration.milliseconds(100));
        config.setMaxLockExpiryRetries(3);
        config.setRetryCounterTtl(Duration.minutes(10));
        config.setClaimIntervalRatio(10);
        config.setPendingMessageDuration(Duration.minutes(10));
        config.setMaxRetries(3);
        return config;
    }
}
