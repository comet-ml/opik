package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("long-window pass")
class LongWindowPassTest {

    private final LockService lockService = mock(LockService.class);
    private final LongWindowPass pass = new LongWindowPass(config(), lockService);

    @Test
    @DisplayName("Redis down at the start: the pass is not due, so the tick reads the short window")
    void startFails() {
        when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.error(new IllegalStateException("redis")));

        assertThat(pass.startIfDue().block()).isFalse();
    }

    @Test
    @DisplayName("Redis down at the finish: the tick still completes")
    void finishFails() {
        when(lockService.unlockUsingToken(any())).thenReturn(Mono.error(new IllegalStateException("redis")));

        pass.finish(Instant.now(), true).block();

        verify(lockService).unlockUsingToken(LongWindowPass.LOCK);
    }

    @Test
    @DisplayName("not drained: the lock is set for the time left to start + 1.5 tick intervals")
    void notDrained() {
        when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());
        when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
        var start = Instant.now();

        pass.finish(start, false).block();

        var held = ArgumentCaptor.forClass(java.time.Duration.class);
        verify(lockService).lockUsingToken(eq(LongWindowPass.LOCK), held.capture());
        var elapsed = java.time.Duration.between(start, Instant.now());
        assertThat(held.getValue()).isBetween(java.time.Duration.ofSeconds(90).minus(elapsed),
                java.time.Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("not drained and already past start + 1.5 tick intervals: the lock is only released, so the pass is "
            + "due at once")
    void notDrainedPastDue() {
        when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());

        pass.finish(Instant.now().minusSeconds(91), false).block();

        verify(lockService).unlockUsingToken(LongWindowPass.LOCK);
        verify(lockService, never()).lockUsingToken(any(), any());
    }

    private static TopicsConfig config() {
        var vectors = new TopicsConfig.VectorsConfig();
        vectors.setTickInterval(Duration.minutes(1));
        vectors.setLongWindowInterval(Duration.days(1));
        var config = new TopicsConfig();
        config.setVectors(vectors);
        return config;
    }
}
