package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The pass's slicing, which needs no store; {@link TopicsStorageTest} runs the pass on real ClickHouse and MySQL. */
@DisplayName("topics labelling pass slices")
class TopicWindowLabelServiceTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    @DisplayName("the slices cover the window exactly: each starts where the one before ends")
    void slicesCoverTheWindow() {
        var first = Instant.parse("2026-10-10T00:00:00Z");
        var second = Instant.parse("2026-10-20T00:00:00Z");

        assertThat(TopicWindowLabelService.slices(FROM, TO, List.of(first, second))).containsExactly(
                new TopicWindowLabelService.Slice(FROM, first),
                new TopicWindowLabelService.Slice(first, second),
                new TopicWindowLabelService.Slice(second, TO));
    }

    @Test
    @DisplayName("ties on a bound, and a bound at the window's start, make no empty slice")
    void tiesMakeNoEmptySlice() {
        var bound = Instant.parse("2026-10-10T00:00:00Z");

        assertThat(TopicWindowLabelService.slices(FROM, TO, List.of(FROM, bound, bound))).containsExactly(
                new TopicWindowLabelService.Slice(FROM, bound),
                new TopicWindowLabelService.Slice(bound, TO));
    }

    @Test
    @DisplayName("a window with no bound is one slice")
    void noBound() {
        assertThat(TopicWindowLabelService.slices(FROM, TO, List.of()))
                .containsExactly(new TopicWindowLabelService.Slice(FROM, TO));
    }
}
