package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    class BelowCeiling {

        @Test
        @DisplayName("everything is added when the batch fits")
        void everythingFits() {
            var eligible = ids(3);

            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, 10, 2))
                    .isEqualTo(eligible);
        }

        @Test
        @DisplayName("a batch that exactly consumes the remaining room is added whole")
        void exactlyFills() {
            var eligible = ids(3);

            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, 10, 7))
                    .isEqualTo(eligible);
        }

        @Test
        @DisplayName("an empty queue with the ceiling as the batch size is added whole")
        void emptyQueue() {
            var eligible = ids(5);

            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, 5, 0))
                    .isEqualTo(eligible);
        }
    }

    @Nested
    @DisplayName("At or over the ceiling")
    class AtOrOverCeiling {

        @Test
        @DisplayName("nothing is added once the queue holds exactly the ceiling")
        void exactlyAtCeiling() {
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, ids(3), 10, 10))
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing is added when the queue is already over the ceiling")
        void overCeiling() {
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, ids(3), 10, 25))
                    .isEmpty();
        }

        @Test
        @DisplayName("a ceiling of one keeps a second item out")
        void ceilingOfOne() {
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, ids(4), 1, 1))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Partial fill")
    class PartialFill {

        @Test
        @DisplayName("only the remaining room is filled, not the whole batch")
        void fillsRemainingRoom() {
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, ids(10), 10, 7))
                    .hasSize(3);
        }

        @Test
        @DisplayName("room for one takes one")
        void roomForOne() {
            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, ids(500), 10, 9))
                    .hasSize(1);
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

        @Test
        @DisplayName("retained items are a subset of what was offered")
        void retainsOnlyOfferedItems() {
            var eligible = ids(10);

            assertThat(AnnotationQueueServiceImpl.fillToMaxItems(QUEUE_ID, eligible, 6, 3))
                    .hasSize(3)
                    .isSubsetOf(eligible);
        }
    }
}
