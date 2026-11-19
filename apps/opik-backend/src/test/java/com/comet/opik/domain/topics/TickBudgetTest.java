package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("tick budget")
class TickBudgetTest {

    private static final Instant START = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    @DisplayName("a read needs a whole second left, and its max_execution_time is the whole seconds left, capped")
    void reads() {
        var clock = new TopicsTestClock(START);
        var budget = TickBudget.of(START, Duration.ofSeconds(10), clock);

        assertThat(budget.readSeconds(Duration.ofSeconds(30))).isEqualTo(10);
        assertThat(budget.readSeconds(Duration.ofSeconds(4))).isEqualTo(4);

        clock.advance(Duration.ofMillis(8_500));
        assertThat(budget.hasTimeForRead()).isTrue();
        assertThat(budget.readSeconds(Duration.ofSeconds(30))).isEqualTo(1);

        clock.advance(Duration.ofMillis(600));
        assertThat(budget.hasTimeForRead()).isFalse();
    }

    @Test
    @DisplayName("a call starts only with its whole timeout left")
    void calls() {
        var clock = new TopicsTestClock(START);
        var budget = TickBudget.of(START, Duration.ofSeconds(10), clock);

        clock.advance(Duration.ofSeconds(7));
        assertThat(budget.hasTimeFor(Duration.ofSeconds(3))).isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(budget.hasTimeFor(Duration.ofSeconds(3))).isFalse();
    }
}
