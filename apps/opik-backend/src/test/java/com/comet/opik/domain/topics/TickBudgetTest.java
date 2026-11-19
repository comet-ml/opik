package com.comet.opik.domain.topics;

import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("tick budget")
class TickBudgetTest {

    private final Instant start = Instant.ofEpochSecond(RandomUtils.secure().randomLong(0, 4_000_000_000L));

    @Test
    @DisplayName("a read needs a whole second plus the embed reserve left, and its max_execution_time is the whole "
            + "seconds before the reserve, capped")
    void reads() {
        var clock = new TopicsTestClock(start);
        var budget = TickBudget.of(start, Duration.ofSeconds(20), clock);
        var reserve = Duration.ofSeconds(10);

        assertThat(budget.readSeconds(Duration.ofSeconds(30), reserve)).isEqualTo(10);
        assertThat(budget.readSeconds(Duration.ofSeconds(4), reserve)).isEqualTo(4);

        clock.advance(Duration.ofMillis(8_500));
        assertThat(budget.hasTimeForRead(reserve)).isTrue();
        assertThat(budget.readSeconds(Duration.ofSeconds(30), reserve)).isEqualTo(1);

        clock.advance(Duration.ofMillis(600));
        assertThat(budget.hasTimeForRead(reserve)).isFalse();
    }

    @Test
    @DisplayName("a call starts only with its whole timeout left")
    void calls() {
        var clock = new TopicsTestClock(start);
        var budget = TickBudget.of(start, Duration.ofSeconds(10), clock);

        clock.advance(Duration.ofSeconds(7));
        assertThat(budget.hasTimeFor(Duration.ofSeconds(3))).isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(budget.hasTimeFor(Duration.ofSeconds(3))).isFalse();
    }
}
