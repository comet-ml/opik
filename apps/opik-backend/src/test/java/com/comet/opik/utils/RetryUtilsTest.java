package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
