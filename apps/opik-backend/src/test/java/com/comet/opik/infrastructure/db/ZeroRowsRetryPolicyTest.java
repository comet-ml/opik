package com.comet.opik.infrastructure.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ZeroRowsRetryPolicy")
class ZeroRowsRetryPolicyTest {

    // Tiny backoffs so the test doesn't actually wait between retries.
    private final ZeroRowsRetryPolicy policy = new ZeroRowsRetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5));

    @Test
    @DisplayName("expectedRows <= 0: 0-row result passes through without retry")
    void bypass_whenNoRowsExpected() {
        AtomicInteger calls = new AtomicInteger();
        Mono<Long> op = Mono.fromSupplier(() -> {
            calls.incrementAndGet();
            return 0L;
        });

        StepVerifier.create(policy.retryOnZeroRows(op, 0L, "copy"))
                .expectNext(0L)
                .verifyComplete();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("Non-zero result passes through immediately, no retry")
    void passThrough_whenRowsWritten() {
        AtomicInteger calls = new AtomicInteger();
        Mono<Long> op = Mono.fromSupplier(() -> {
            calls.incrementAndGet();
            return 5L;
        });

        StepVerifier.create(policy.retryOnZeroRows(op, 5L, "copy"))
                .expectNext(5L)
                .verifyComplete();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("0 rows then a non-zero result: retries and succeeds")
    void retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Mono<Long> op = Mono.defer(() -> Mono.just(calls.incrementAndGet() < 3 ? 0L : 7L));

        StepVerifier.create(policy.retryOnZeroRows(op, 7L, "copy"))
                .expectNext(7L)
                .verifyComplete();

        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("Always 0 rows: exhausts retries and surfaces ZeroRowsWrittenException")
    void exhaustsAndThrows() {
        AtomicInteger calls = new AtomicInteger();
        Mono<Long> op = Mono.fromSupplier(() -> {
            calls.incrementAndGet();
            return 0L;
        });

        StepVerifier.create(policy.retryOnZeroRows(op, 4L, "copyVersionItems"))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ZeroRowsWrittenException.class)
                        .hasMessageContaining("copyVersionItems")
                        .hasMessageContaining("expected 4"));

        // initial attempt + 3 retries
        assertThat(calls).hasValue(4);
    }
}
