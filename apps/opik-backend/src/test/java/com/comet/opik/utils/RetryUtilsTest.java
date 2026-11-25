package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Retries until a deadline")
class RetryUtilsTest {

    private static final Duration BASE = Duration.ofMillis(100);

    @Test
    @DisplayName("the wait is Retry-After when given, else the base doubled per retry with jitter of up to half")
    void backoffHonorsRetryAfterOrDoublesWithJitter() {
        assertThat(RetryUtils.backoff(BASE, 2, Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(3));
        for (int retries = 0; retries < 4; retries++) {
            var expected = BASE.multipliedBy(1L << retries);
            assertThat(RetryUtils.backoff(BASE, retries, null))
                    .as("retry %d", retries)
                    .isBetween(expected.dividedBy(2), expected.multipliedBy(3).dividedBy(2));
        }
    }

    @Test
    @DisplayName("retries up to the maximum, then fails with the last error")
    void retriesUpToTheMaximum() {
        var attempts = new AtomicInteger();
        var retried = new ArrayList<Long>();

        assertThatThrownBy(() -> failing(attempts).retryWhen(RetryUtils.untilDeadline(2, Duration.ofMillis(1),
                Instant.now().plusSeconds(10), failure -> true, failure -> null,
                (failure, attempt) -> retried.add(attempt))).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("attempt 3");
        assertThat(retried).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("a failure the filter rejects is not retried")
    void doesNotRetryWhatTheFilterRejects() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> failing(attempts).retryWhen(RetryUtils.untilDeadline(2, Duration.ofMillis(1),
                Instant.now().plusSeconds(10), failure -> false, failure -> null, (failure, attempt) -> {
                })).block())
                .hasMessage("attempt 1");
    }

    @Test
    @DisplayName("a retry whose wait would end past the deadline is not made")
    void doesNotRetryPastTheDeadline() {
        var attempts = new AtomicInteger();
        List<Long> retried = new ArrayList<>();

        assertThatThrownBy(() -> failing(attempts).retryWhen(RetryUtils.untilDeadline(2, Duration.ofMillis(1),
                Instant.now().plusSeconds(1), failure -> true, failure -> Duration.ofSeconds(5),
                (failure, attempt) -> retried.add(attempt))).block())
                .hasMessage("attempt 1");
        assertThat(retried).isEmpty();
    }

    private static Mono<Object> failing(AtomicInteger attempts) {
        return Mono.fromCallable(() -> {
            throw new IllegalStateException("attempt " + attempts.incrementAndGet());
        });
    }
}
