package com.comet.opik.domain;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestSuiteAssertionCounterServiceTest {

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private RAtomicLongReactive atomicLong;

    private static final String WORKSPACE_ID = "test-workspace";

    private TestSuiteAssertionCounterService service;

    @BeforeEach
    void setUp() {
        var config = new ExperimentExecutionConfig();
        config.setBatchCounterTtl(Duration.hours(24));

        service = new TestSuiteAssertionCounterService(redisClient, config, experimentService);
        when(redisClient.getAtomicLong(anyString())).thenReturn(atomicLong);
    }

    @Test
    void setCounters() {
        var experimentA = UUID.randomUUID();
        var experimentB = UUID.randomUUID();

        when(atomicLong.set(any(Long.class))).thenReturn(Mono.empty());
        when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));

        service.setCounters(WORKSPACE_ID, Map.of(experimentA, 3L, experimentB, 2L)).block();

        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisClient, atLeast(2)).getAtomicLong(keyCaptor.capture());

        var keys = keyCaptor.getAllValues();
        assertThat(keys).contains(
                ExperimentExecutionConfig.TEST_SUITE_ASSERTION_COUNTER_KEY_PREFIX + WORKSPACE_ID + ":" + experimentA,
                ExperimentExecutionConfig.TEST_SUITE_ASSERTION_COUNTER_KEY_PREFIX + WORKSPACE_ID + ":" + experimentB);
    }

    @Test
    void decrementAndFinishIfCompleteWhenCounterAboveZero() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(true));
        when(atomicLong.decrementAndGet()).thenReturn(Mono.just(2L));
        when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));

        service.decrementAndFinishIfComplete(WORKSPACE_ID, experimentId).block();

        verify(atomicLong).decrementAndGet();
        verify(experimentService, never()).update(any(), any());
        verify(experimentService, never()).finishExperiments(any());
    }

    @Test
    void decrementAndFinishIfCompleteWhenCounterReachesZero() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(true));
        when(atomicLong.decrementAndGet()).thenReturn(Mono.just(0L));
        when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
        when(experimentService.update(any(UUID.class), any(ExperimentUpdate.class))).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        service.decrementAndFinishIfComplete(WORKSPACE_ID, experimentId).block();

        var expectedUpdate = ExperimentUpdate.builder().status(ExperimentStatus.COMPLETED).build();
        verify(experimentService).update(experimentId, expectedUpdate);
        verify(experimentService).finishExperiments(Set.of(experimentId));
    }

    @Test
    void decrementAndFinishIfCompleteWhenCounterGoesBelowZero() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(true));
        when(atomicLong.decrementAndGet()).thenReturn(Mono.just(-1L));
        when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
        when(experimentService.update(any(UUID.class), any(ExperimentUpdate.class))).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        service.decrementAndFinishIfComplete(WORKSPACE_ID, experimentId).block();

        var expectedUpdate = ExperimentUpdate.builder().status(ExperimentStatus.COMPLETED).build();
        verify(experimentService).update(experimentId, expectedUpdate);
        verify(experimentService).finishExperiments(Set.of(experimentId));
    }

    @Test
    void decrementAndFinishIfCompleteWhenKeyDoesNotExist() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(false));

        service.decrementAndFinishIfComplete(WORKSPACE_ID, experimentId).block();

        verify(atomicLong, never()).decrementAndGet();
        verify(experimentService, never()).update(any(), any());
        verify(experimentService, never()).finishExperiments(any());
    }

    @Test
    void decrementAndFinishIfCompleteWhenFinishFailsPropagatesError() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(true));
        when(atomicLong.decrementAndGet()).thenReturn(Mono.just(0L));
        when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
        when(experimentService.update(eq(experimentId), any(ExperimentUpdate.class)))
                .thenReturn(Mono.error(new RuntimeException("update failed")));
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.decrementAndFinishIfComplete(WORKSPACE_ID, experimentId).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("update failed");
    }

    @Test
    void exists() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(true));

        var result = service.exists(WORKSPACE_ID, experimentId).block();

        assertThat(result).isTrue();
    }

    @Test
    void existsWhenNotPresent() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.isExists()).thenReturn(Mono.just(false));

        var result = service.exists(WORKSPACE_ID, experimentId).block();

        assertThat(result).isFalse();
    }

    @Test
    void adjust() {
        var experimentId = UUID.randomUUID();

        when(atomicLong.addAndGet(5L)).thenReturn(Mono.just(8L));
        when(atomicLong.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));

        var result = service.adjust(WORKSPACE_ID, experimentId, 5L).block();

        assertThat(result).isEqualTo(8L);
    }

}
