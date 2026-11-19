package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsWorkspaceNamesTest {

    @Test
    @DisplayName("A failed lookup or a blank name falls back to the id")
    void fallsBackToTheId() {
        var failing = UUID.randomUUID().toString();
        var blank = UUID.randomUUID().toString();
        var names = new TopicsWorkspaceNames(id -> {
            if (id.equals(failing)) {
                throw new IllegalStateException("react service down");
            }
            return " ";
        }, new TopicsTestClock(Instant.now()));

        assertThat(names.of(failing).get(TopicsWorkspaceNames.WORKSPACE_NAME)).isEqualTo(failing);
        assertThat(names.of(blank).get(TopicsWorkspaceNames.WORKSPACE_NAME)).isEqualTo(blank);
    }

    @Test
    @DisplayName("A failed lookup is not retried for 10 minutes")
    void failedLookupBacksOff() {
        var workspaceId = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        var clock = new TopicsTestClock(Instant.now());
        var names = new TopicsWorkspaceNames(id -> {
            calls.incrementAndGet();
            throw new IllegalStateException("react service down");
        }, clock);

        names.name(workspaceId);
        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        names.name(workspaceId);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMillis(1));
        names.name(workspaceId);
        assertThat(calls).hasValue(2);
    }
}
