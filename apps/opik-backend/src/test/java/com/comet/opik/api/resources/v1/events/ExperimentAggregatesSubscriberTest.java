package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ExperimentAggregationMessage;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
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
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private LockService lockService;

    private ExperimentDenormalizationConfig config;
    private ExperimentAggregatesSubscriber subscriber;

    @BeforeEach
    void setUp() {
        config = buildConfig(true);
        subscriber = new ExperimentAggregatesSubscriber(config, redisson, experimentAggregatesService, lockService);
    }

    @Nested
    class Lifecycle {

        @Test
        void startSkipsStartupWhenDisabled() {
            config = buildConfig(false);
            subscriber = new ExperimentAggregatesSubscriber(config, redisson, experimentAggregatesService, lockService);

            subscriber.start();

            verify(redisson, never()).getStream(any(), any());
        }

        @Test
        void stopSkipsShutdownWhenDisabled() {
            config = buildConfig(false);
            subscriber = new ExperimentAggregatesSubscriber(config, redisson, experimentAggregatesService, lockService);

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

            when(lockService.executeWithLockCustomExpire(any(Lock.class), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(experimentAggregatesService.populateAggregations(experimentId))
                    .thenReturn(Mono.empty());

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyComplete();

            var lockCaptor = ArgumentCaptor.forClass(Lock.class);
            verify(lockService).executeWithLockCustomExpire(lockCaptor.capture(), any(), any());
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

            when(lockService.executeWithLockCustomExpire(any(Lock.class), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(experimentAggregatesService.populateAggregations(experimentId))
                    .thenReturn(Mono.error(expectedError));

            StepVerifier.create(subscriber.processEvent(message))
                    .verifyErrorMatches(e -> e == expectedError);
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
        config.setAggregationLockTime(Duration.seconds(120));
        config.setClaimIntervalRatio(10);
        config.setPendingMessageDuration(Duration.minutes(10));
        config.setMaxRetries(3);
        return config;
    }
}
