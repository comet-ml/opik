package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsWorkspaceNamesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Test
    @DisplayName("The name is looked up once per TTL and paired with the id")
    void nameIsCached() {
        var calls = new AtomicInteger();
        var names = new TopicsWorkspaceNames(id -> {
            calls.incrementAndGet();
            return "name-" + id;
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(names.name("ws")).isEqualTo("name-ws");
        assertThat(names.of("ws").get(TopicsWorkspaceNames.WORKSPACE_ID)).isEqualTo("ws");
        assertThat(names.of("ws").get(TopicsWorkspaceNames.WORKSPACE_NAME)).isEqualTo("name-ws");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("A blank name or a failed lookup falls back to the id and is retried after the failure TTL")
    void fallsBackToTheId() {
        var calls = new AtomicInteger();
        var clock = new MutableClock(NOW);
        var names = new TopicsWorkspaceNames(id -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("react service down");
            }
            return " ";
        }, clock);

        assertThat(names.name("ws")).isEqualTo("ws");
        assertThat(names.name("ws")).isEqualTo("ws");
        assertThat(calls).hasValue(1);

        clock.now = NOW.plus(TopicsWorkspaceNames.FAILURE_TTL);
        assertThat(names.name("ws")).isEqualTo("ws");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("A resolved name passed in is used as is, and a blank one falls back to the id")
    void staticBuilderFallsBack() {
        assertThat(TopicsWorkspaceNames.builder("ws", "acme").build().get(TopicsWorkspaceNames.WORKSPACE_NAME))
                .isEqualTo("acme");
        assertThat(TopicsWorkspaceNames.builder("ws", "").build().get(TopicsWorkspaceNames.WORKSPACE_NAME))
                .isEqualTo("ws");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
