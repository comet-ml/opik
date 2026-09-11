package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Annotation Queue Automation Item Ceiling")
class AnnotationQueueMaxItemsTest {

    private static final UUID QUEUE_ID = UUID.randomUUID();

    private static Set<UUID> ids(int count) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    @Nested
    @DisplayName("Below the ceiling")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BelowCeiling {

        private Stream<Arguments> fittingBatches() {
            return Stream.of(
                    arguments(3, 10, 2L, "room to spare"),
                    arguments(3, 10, 7L, "batch exactly consumes the remaining room"),
                    arguments(5, 5, 0L, "empty queue, batch the size of the ceiling"),
                    arguments(1, 1, 0L, "ceiling of one, empty queue"));
        }

        @ParameterizedTest(name = "{3}")
        @MethodSource("fittingBatches")
        @DisplayName("adds the whole batch when it fits:")
        void addsWholeBatch(int batchSize, int maxItemsInQueue, long held, String label) {
            var eligible = ids(batchSize);

            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, maxItemsInQueue, held))
                    .isEqualTo(eligible);
        }
    }

    @Nested
    @DisplayName("At or over the ceiling")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AtOrOverCeiling {

        private Stream<Arguments> fullQueues() {
            return Stream.of(
                    arguments(3, 10, 10L, "exactly at the ceiling"),
                    arguments(3, 10, 25L, "already over the ceiling"),
                    arguments(4, 1, 1L, "ceiling of one, already taken"));
        }

        @ParameterizedTest(name = "{3}")
        @MethodSource("fullQueues")
        @DisplayName("adds nothing:")
        void addsNothing(int batchSize, int maxItemsInQueue, long held, String label) {
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, ids(batchSize), maxItemsInQueue, held))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Partial fill")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PartialFill {

        private Stream<Arguments> partialBatches() {
            return Stream.of(
                    arguments(10, 10, 7L, 3, "room for three of ten"),
                    arguments(500, 10, 9L, 1, "room for one of a full batch"),
                    arguments(10, 6, 3L, 3, "room for three of ten, lower ceiling"));
        }

        @ParameterizedTest(name = "{4}")
        @MethodSource("partialBatches")
        @DisplayName("fills only the remaining room:")
        void fillsRemainingRoom(int batchSize, int maxItemsInQueue, long held, int expected, String label) {
            var eligible = ids(batchSize);

            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, maxItemsInQueue, held))
                    .hasSize(expected)
                    .isSubsetOf(eligible);
        }

        @Test
        @DisplayName("which items land is the id order, not hash order")
        void deterministicSelection() {
            var eligible = ids(10);
            List<UUID> expected = eligible.stream().sorted().limit(4).toList();

            var retained = AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, 4, 0);

            assertThat(retained).containsExactlyElementsOf(expected);
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, 4, 0))
                    .containsExactlyElementsOf(retained);
        }
    }
}
