package com.comet.opik.domain;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssertionCounterService Tests")
class AssertionCounterServiceTest {

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private RAtomicLongReactive atomicLong;

    private AssertionCounterService service;

    @BeforeEach
    void setUp() {
        var config = new ExperimentExecutionConfig();
        config.setBatchCounterTtl(Duration.hours(24));
        service = new AssertionCounterService(redisClient, experimentService, config);
    }

    @Nested
    @DisplayName("setCounters")
    class SetCounters {

        @Test
        @DisplayName("sets counters with TTL for each experiment")
        void setsCountersWithTtl() {
            UUID exp1 = UUID.randomUUID();
            UUID exp2 = UUID.randomUUID();

            @SuppressWarnings("unchecked")
            RAtomicLongReactive atomicLong1 = org.mockito.Mockito.mock(RAtomicLongReactive.class);
            @SuppressWarnings("unchecked")
            RAtomicLongReactive atomicLong2 = org.mockito.Mockito.mock(RAtomicLongReactive.class);

            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + exp1))
                    .thenReturn(atomicLong1);
            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + exp2))
                    .thenReturn(atomicLong2);

            when(atomicLong1.set(3L)).thenReturn(Mono.empty());
            when(atomicLong1.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
            when(atomicLong2.set(2L)).thenReturn(Mono.empty());
            when(atomicLong2.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));

            StepVerifier.create(service.setCounters(Map.of(exp1, 3L, exp2, 2L)))
                    .verifyComplete();

            verify(atomicLong1).set(3L);
            verify(atomicLong1).expire(java.time.Duration.ofHours(24));
            verify(atomicLong2).set(2L);
            verify(atomicLong2).expire(java.time.Duration.ofHours(24));
        }

        @Test
        @DisplayName("handles empty map")
        void handlesEmptyMap() {
            StepVerifier.create(service.setCounters(Map.of()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("exists")
    class Exists {

        @Test
        @DisplayName("returns true when counter exists")
        void returnsTrueWhenExists() {
            UUID experimentId = UUID.randomUUID();
            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.isExists()).thenReturn(Mono.just(true));

            StepVerifier.create(service.exists(experimentId))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns false when counter does not exist")
        void returnsFalseWhenNotExists() {
            UUID experimentId = UUID.randomUUID();
            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.isExists()).thenReturn(Mono.just(false));

            StepVerifier.create(service.exists(experimentId))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("decrement")
    class Decrement {

        @Test
        @DisplayName("decrements and returns remaining count")
        void decrementsAndReturnsRemaining() {
            UUID experimentId = UUID.randomUUID();
            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.decrementAndGet()).thenReturn(Mono.just(2L));

            StepVerifier.create(service.decrement(experimentId))
                    .expectNext(2L)
                    .verifyComplete();

            verify(experimentService, never()).update(any(), any());
            verify(experimentService, never()).finishExperiments(any());
        }
    }

    @Nested
    @DisplayName("adjust")
    class Adjust {

        @Test
        @DisplayName("adjusts counter by delta")
        void adjustsByDelta() {
            UUID experimentId = UUID.randomUUID();
            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.addAndGet(2L)).thenReturn(Mono.just(5L));

            StepVerifier.create(service.adjust(experimentId, 2L))
                    .expectNext(5L)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("deleteCounters")
    class DeleteCounters {

        @Test
        @DisplayName("deletes counter keys for all experiment IDs")
        void deletesCounterKeys() {
            UUID exp1 = UUID.randomUUID();
            UUID exp2 = UUID.randomUUID();

            @SuppressWarnings("unchecked")
            RAtomicLongReactive atomicLong1 = org.mockito.Mockito.mock(RAtomicLongReactive.class);
            @SuppressWarnings("unchecked")
            RAtomicLongReactive atomicLong2 = org.mockito.Mockito.mock(RAtomicLongReactive.class);

            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + exp1))
                    .thenReturn(atomicLong1);
            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + exp2))
                    .thenReturn(atomicLong2);
            when(atomicLong1.delete()).thenReturn(Mono.just(true));
            when(atomicLong2.delete()).thenReturn(Mono.just(true));

            StepVerifier.create(service.deleteCounters(List.of(exp1, exp2)))
                    .verifyComplete();

            verify(atomicLong1).delete();
            verify(atomicLong2).delete();
        }

        @Test
        @DisplayName("handles empty collection")
        void handlesEmptyCollection() {
            StepVerifier.create(service.deleteCounters(List.of()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("decrementAndFinishIfComplete")
    class DecrementAndFinishIfComplete {

        @Test
        @DisplayName("finishes experiment when counter reaches zero")
        void finishesWhenCounterReachesZero() {
            UUID experimentId = UUID.randomUUID();
            String workspaceId = "workspace-1";
            String userName = "user-1";

            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.decrementAndGet()).thenReturn(Mono.just(0L));
            when(experimentService.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());
            when(experimentService.finishExperiments(Set.of(experimentId)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.decrementAndFinishIfComplete(experimentId, workspaceId, userName))
                    .verifyComplete();

            ArgumentCaptor<ExperimentUpdate> updateCaptor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService).update(eq(experimentId), updateCaptor.capture());
            assertThat(updateCaptor.getValue().status()).isEqualTo(ExperimentStatus.COMPLETED);
            verify(experimentService).finishExperiments(Set.of(experimentId));
        }

        @Test
        @DisplayName("does not finish experiment when counter is still positive")
        void doesNotFinishWhenCounterPositive() {
            UUID experimentId = UUID.randomUUID();
            String workspaceId = "workspace-1";
            String userName = "user-1";

            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.decrementAndGet()).thenReturn(Mono.just(3L));

            StepVerifier.create(service.decrementAndFinishIfComplete(experimentId, workspaceId, userName))
                    .verifyComplete();

            verify(experimentService, never()).update(any(), any());
            verify(experimentService, never()).finishExperiments(any());
        }

        @Test
        @DisplayName("finishes experiment when counter goes negative")
        void finishesWhenCounterNegative() {
            UUID experimentId = UUID.randomUUID();
            String workspaceId = "workspace-1";
            String userName = "user-1";

            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.decrementAndGet()).thenReturn(Mono.just(-1L));
            when(experimentService.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());
            when(experimentService.finishExperiments(Set.of(experimentId)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.decrementAndFinishIfComplete(experimentId, workspaceId, userName))
                    .verifyComplete();

            verify(experimentService).update(eq(experimentId), any(ExperimentUpdate.class));
            verify(experimentService).finishExperiments(Set.of(experimentId));
        }

        @Test
        @DisplayName("handles experiment service error gracefully")
        void handlesExperimentServiceError() {
            UUID experimentId = UUID.randomUUID();
            String workspaceId = "workspace-1";
            String userName = "user-1";

            when(redisClient.getAtomicLong(ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId))
                    .thenReturn(atomicLong);
            when(atomicLong.decrementAndGet()).thenReturn(Mono.just(0L));
            when(experimentService.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.error(new RuntimeException("DB error")));

            StepVerifier.create(service.decrementAndFinishIfComplete(experimentId, workspaceId, userName))
                    .verifyComplete();
        }
    }
}
