package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.redisson.api.RMapReactive;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExperimentDenormalizationJob Tests")
class ExperimentDenormalizationJobTest {

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private LockService lockService;

    @Mock
    private RScoredSortedSetReactive<Object> scoredSortedSet;

    @Mock
    private RMapReactive<String, String> bucket;

    @Mock
    @SuppressWarnings("rawtypes")
    private RStreamReactive stream;

    @Mock
    private JobExecutionContext jobContext;

    private ExperimentDenormalizationConfig config;
    private ExperimentDenormalizationJob job;

    @BeforeEach
    void setUp() {
        config = buildConfig(true);
        job = new ExperimentDenormalizationJob(config, redisClient, lockService);
    }

    @Nested
    @DisplayName("Disabled configuration")
    class WhenDisabled {

        @Test
        @DisplayName("Should skip all processing when disabled")
        void doJob__whenDisabled__shouldSkipProcessing() {
            config = buildConfig(false);
            job = new ExperimentDenormalizationJob(config, redisClient, lockService);

            job.doJob(jobContext);

            verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any());
            verify(redisClient, never()).getScoredSortedSet(anyString());
        }
    }

    @Nested
    @DisplayName("Lock acquisition")
    class LockAcquisition {

        @Test
        @DisplayName("Should skip processing when lock cannot be acquired")
        void doJob__whenLockNotAcquired__shouldSkipProcessing() {
            when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(2));

            job.doJob(jobContext);

            verify(redisClient, never()).getScoredSortedSet(anyString());
        }
    }

    @Nested
    @DisplayName("No experiments ready")
    class NoExperimentsReady {

        @Test
        @DisplayName("Should complete without processing when ZSET is empty")
        void doJob__whenNoExperimentsReady__shouldComplete() {
            setupLockToRunAction();

            when(redisClient.getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY))
                    .thenReturn(scoredSortedSet);
            when(scoredSortedSet.valueRange(any(Double.class), eq(true), any(Double.class), eq(true), anyInt(),
                    anyInt()))
                    .thenReturn(Mono.just(List.of()));

            job.doJob(jobContext);

            verify(redisClient, never()).getMap(anyString());
            verify(redisClient, never()).getStream(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Experiment processing - happy path")
    class HappyPath {

        @Test
        @DisplayName("Should publish stream message and clean up Redis state for ready experiment")
        @SuppressWarnings("unchecked")
        void doJob__whenExperimentReady__shouldPublishMessageAndCleanup() {
            var workspaceId = UUID.randomUUID().toString();
            var experimentId = UUID.randomUUID();
            var member = workspaceId + ":" + experimentId;
            var userName = "test-user";
            var streamMessageId = new StreamMessageId(0L, 1L);

            setupLockToRunAction();

            when(redisClient.getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY))
                    .thenReturn(scoredSortedSet);
            when(scoredSortedSet.valueRange(any(Double.class), eq(true), any(Double.class), eq(true), anyInt(),
                    anyInt()))
                    .thenReturn(Mono.just(List.of(member)));

            when(redisClient.<String, String>getMap(
                    ExperimentDenormalizationConfig.EXPERIMENT_KEY_PREFIX + member))
                    .thenReturn(bucket);
            when(bucket.get(ExperimentDenormalizationConfig.USER_NAME_FIELD)).thenReturn(Mono.just(userName));
            when(bucket.delete()).thenReturn(Mono.just(true));

            doReturn(stream).when(redisClient).getStream(anyString(), any());
            when(stream.add(any())).thenReturn(Mono.just(streamMessageId));

            when(scoredSortedSet.remove(member)).thenReturn(Mono.just(true));

            job.doJob(jobContext);

            verify(stream).add(any());
            verify(bucket).delete();
            verify(scoredSortedSet).remove(member);
        }
    }

    @Nested
    @DisplayName("Stale ZSET entry")
    class StaleEntry {

        @Test
        @DisplayName("When hash expired, should remove ZSET entry without publishing")
        void doJob__whenHashExpired__shouldRemoveStaleZsetEntry() {
            var workspaceId = UUID.randomUUID().toString();
            var experimentId = UUID.randomUUID();
            var member = workspaceId + ":" + experimentId;

            setupLockToRunAction();

            when(redisClient.getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY))
                    .thenReturn(scoredSortedSet);
            when(scoredSortedSet.valueRange(any(Double.class), eq(true), any(Double.class), eq(true), anyInt(),
                    anyInt()))
                    .thenReturn(Mono.just(List.of(member)));

            when(redisClient.<String, String>getMap(
                    ExperimentDenormalizationConfig.EXPERIMENT_KEY_PREFIX + member))
                    .thenReturn(bucket);
            // Simulate expired hash — bucket.get() returns empty
            when(bucket.get(ExperimentDenormalizationConfig.USER_NAME_FIELD)).thenReturn(Mono.empty());

            when(scoredSortedSet.remove(member)).thenReturn(Mono.just(true));

            job.doJob(jobContext);

            verify(stream, never()).add(any());
            verify(bucket, never()).delete();
            verify(scoredSortedSet).remove(member);
        }
    }

    @Nested
    @DisplayName("Multiple experiments in batch")
    class MultipleBatch {

        @Test
        @DisplayName("Should process all ready experiments in a single job run")
        @SuppressWarnings("unchecked")
        void doJob__withMultipleExperiments__shouldProcessAll() {
            var workspaceId = UUID.randomUUID().toString();
            var experimentId1 = UUID.randomUUID();
            var experimentId2 = UUID.randomUUID();
            var member1 = workspaceId + ":" + experimentId1;
            var member2 = workspaceId + ":" + experimentId2;
            var userName = "test-user";
            var streamMessageId = new StreamMessageId(0L, 1L);

            setupLockToRunAction();

            when(redisClient.getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY))
                    .thenReturn(scoredSortedSet);
            when(scoredSortedSet.valueRange(any(Double.class), eq(true), any(Double.class), eq(true), anyInt(),
                    anyInt()))
                    .thenReturn(Mono.just(List.of(member1, member2)));

            RMapReactive<String, String> bucket1 = mock(RMapReactive.class);
            RMapReactive<String, String> bucket2 = mock(RMapReactive.class);

            when(redisClient.<String, String>getMap(
                    ExperimentDenormalizationConfig.EXPERIMENT_KEY_PREFIX + member1))
                    .thenReturn(bucket1);
            when(redisClient.<String, String>getMap(
                    ExperimentDenormalizationConfig.EXPERIMENT_KEY_PREFIX + member2))
                    .thenReturn(bucket2);

            when(bucket1.get("userName")).thenReturn(Mono.just(userName));
            when(bucket1.delete()).thenReturn(Mono.just(true));

            when(bucket2.get("userName")).thenReturn(Mono.just(userName));
            when(bucket2.delete()).thenReturn(Mono.just(true));

            doReturn(stream).when(redisClient).getStream(anyString(), any());
            when(stream.add(any())).thenReturn(Mono.just(streamMessageId));

            when(scoredSortedSet.remove(member1)).thenReturn(Mono.just(true));
            when(scoredSortedSet.remove(member2)).thenReturn(Mono.just(true));

            job.doJob(jobContext);

            verify(stream, times(2)).add(any());
            verify(bucket1).delete();
            verify(bucket2).delete();
            verify(scoredSortedSet).remove(member1);
            verify(scoredSortedSet).remove(member2);
        }
    }

    private void setupLockToRunAction() {
        when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(1));
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
        config.setJobBatchSize(100);
        config.setJobInterval(Duration.seconds(5));
        return config;
    }
}
