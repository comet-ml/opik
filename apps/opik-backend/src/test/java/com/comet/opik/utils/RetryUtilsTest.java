package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("a caller under onErrorContinue still gets the last error when the retries run out")
    void lastErrorReachesACallerUnderOnErrorContinue() {
        var attempts = new AtomicInteger();
        var continued = new AtomicInteger();
        var call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.<String>error(new IllegalStateException("failed"));
        }).retryWhen(RetryUtils.untilDeadline(2, Duration.ofMillis(1), Instant.now().plusSeconds(30),
                failure -> true, failure -> null, (failure, attempt) -> {
                }));

        // A stream consumer's pipeline: the call's own error handling, with onErrorContinue after it.
        StepVerifier.create(Flux.just(1)
                .concatMap(message -> call.onErrorResume(failure -> Mono.just(failure.getMessage())))
                .onErrorContinue((failure, value) -> continued.incrementAndGet()))
                .expectNext("failed")
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        assertThat(attempts).hasValue(3);
        assertThat(continued).hasValue(0);
    }
}
