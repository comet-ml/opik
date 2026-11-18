package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The nightly update's rules on small synthetic inputs: each step alone, then whole nights. */
@DisplayName("topics nightly update: rules")
class TopicsNightlyRulesTest {

    private static final LocalDate FIRST_BUILD = LocalDate.parse("2026-10-01");
    private static final LocalDate NIGHT = LocalDate.parse("2026-10-20");
    private static final Instant NIGHT_START = Instant.parse("2026-10-20T00:00:00Z");
    /** Inside the night's last 7 days. */
    private static final Instant WINDOW_DAY = Instant.parse("2026-10-15T00:00:00Z");
    private static final float[] EAST = {1, 0};
    private static final float[] SOUTH = {0, -1};
    private static final float[] WEST = {-1, 0};

    @Nested
    @DisplayName("lifecycle")
    class Lifecycles {

        @Test
        @DisplayName("a third low night retires, a good night resets the streak")
        void lowShare() {
            var low = topic("Low").toBuilder().lowNights(2).build();
            var reset = topic("Reset").toBuilder().lowNights(2).build();
            var state = state(0, low, reset, topic("Big"));
            var labels = new int[300];
            Arrays.fill(labels, 2);
            labels[0] = 0;
            labels[1] = 1;
            labels[2] = 1;

            Lifecycle.retire(state, window(300, Instant.parse("2026-10-15T00:00:00Z")), labels);

            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(state.topic(1).getLowNights()).isZero();
            assertThat(state.live()).containsExactly(1, 2);
        }

        @ParameterizedTest(name = "{0} held traces: retires on night {1}")
        @CsvSource({"0, 1", "1, 3"})
        @DisplayName("a topic that holds no trace retires the same night; a small one that holds a trace needs three "
                + "low nights")
        void lowNightsByHeldTraces(int held, int nights) {
            var state = state(0, topic("Small"), topic("Big"));
            var labels = new int[300];
            Arrays.fill(labels, 1);
            Arrays.fill(labels, 0, held, 0);

            for (int night = 1; night < nights; night++) {
                Lifecycle.retire(state, window(300, WINDOW_DAY), labels);

                assertThat(state.topic(0).getLowNights()).isEqualTo(night);
                assertThat(state.live()).containsExactly(0, 1);
            }

            Lifecycle.retire(state, window(300, WINDOW_DAY), labels);

            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(state.live()).containsExactly(1);
        }

        @Test
        @DisplayName("a topic born after the first build is measured on the whole window, as any other")
        void newborn() {
            var state = state(0, topic("Old"), topic("New", LocalDate.parse("2026-10-18")));
            var createdAt = new Instant[300];
            Arrays.fill(createdAt, 0, 299, Instant.parse("2026-10-01T00:00:00Z"));
            createdAt[299] = Instant.parse("2026-10-19T00:00:00Z");
            var labels = new int[300];
            labels[299] = 1;

            Lifecycle.retire(state, window(createdAt), labels);

            assertThat(state.topic(1).getLowNights()).isEqualTo(1);
            assertThat(state.topic(0).getLowNights()).isZero();
        }

        @Test
        @DisplayName("a live topic's size is its count in the labels")
        void sizes() {
            var state = state(0, topic("Quiet"), topic("Busy"));
            var labels = new int[100];
            Arrays.fill(labels, 1);

            Lifecycle.sizes(state, labels);

            assertThat(state.topic(0).getSize()).isZero();
            assertThat(state.topic(1).getSize()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("candidates")
    class Candidates {

        @Test
        @DisplayName("a group's share and last-7-days share are counted over the whole window, not the Other traces")
        void sharesOfWholeWindow() {
            // 60 East traces have an owner; 20 South and 20 West are Other. Recent: 40 East, 10 South, 20 West.
            var vectors = three(60, 20, 20);
            var old = Instant.parse("2026-09-25T00:00:00Z");
            var createdAt = new Instant[100];
            for (int i = 0; i < createdAt.length; i++) {
                boolean recent = i < 40 || (i >= 60 && i < 70) || i >= 80;
                createdAt[i] = recent ? WINDOW_DAY : old;
            }
            var unassigned = IntStream.range(60, 100).toArray();

            var grouping = CandidateGrouper.group(window(vectors, createdAt), unassigned, 1);

            assertThat(grouping.candidates()).hasSize(2);
            var south = grouping.groups().get(grouping.labels()[0]);
            var west = grouping.groups().get(grouping.labels()[39]);
            assertThat(south.size()).isEqualTo(20);
            assertThat(south.share()).isEqualTo(0.2);
            assertThat(south.recentShare()).isEqualTo(10.0 / 70);
            assertThat(west.size()).isEqualTo(20);
            assertThat(west.share()).isEqualTo(0.2);
            assertThat(west.recentShare()).isEqualTo(20.0 / 70);
        }
    }

    @Nested
    @DisplayName("churn")
    class Churns {

        private static final int OTHER = PrototypeAssigner.OTHER;

        @ParameterizedTest(name = "[{0}] to [{1}]")
        @CsvSource({"0 0 1 Other, 0 1 Other 0, 0.5, 0.75", "Other, 0, 0, 1"})
        @DisplayName("churn counts topic-to-topic moves, churn_any every move, over every window trace; no trace in a "
                + "topic both before and after is churn 0")
        void churn(String before, String after, double churn, double churnAny) {
            assertThat(TopicChurn.of(labels(before), labels(after))).isEqualTo(new TopicChurn.Churn(churn, churnAny));
        }

        /** Space-separated topic indexes; {@code Other} is {@link #OTHER}. */
        private static int[] labels(String labels) {
            return Arrays.stream(labels.split(" "))
                    .mapToInt(label -> label.equals("Other") ? OTHER : Integer.parseInt(label))
                    .toArray();
        }
    }

    private static NightState state(int rebuilds, NightState.Topic... topics) {
        var ids = new AtomicLong(100);
        return NightState.builder()
                .topics(List.of(topics))
                .otherPrototypes(List.of())
                .rebuilds(rebuilds)
                .night(NIGHT)
                .ids(() -> new UUID(0, ids.getAndIncrement()))
                .build();
    }

    private static NightState.Topic topic(String name, float[]... prototypes) {
        return topic(name, FIRST_BUILD, prototypes);
    }

    private static NightState.Topic topic(String name, LocalDate born, float[]... prototypes) {
        return NightState.Topic.builder()
                .id(UUID.nameUUIDFromBytes(name.getBytes()))
                .name(name)
                .definition(name + " def")
                .born(born)
                .prototypes(List.of(prototypes))
                .build();
    }

    /** {@code east} traces on EAST, then {@code south} on SOUTH, then {@code west} on WEST. */
    private static List<float[]> three(int east, int south, int west) {
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < east + south + west; i++) {
            vectors.add(i < east ? EAST : i < east + south ? SOUTH : WEST);
        }
        return vectors;
    }

    /** {@code n} traces on {@link #EAST} created at {@code at}. */
    private static NightWindow window(int n, Instant at) {
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < n; i++) {
            vectors.add(EAST);
        }
        var createdAt = new Instant[n];
        Arrays.fill(createdAt, at);
        return window(vectors, createdAt);
    }

    private static NightWindow window(Instant[] createdAt) {
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < createdAt.length; i++) {
            vectors.add(EAST);
        }
        return window(vectors, createdAt);
    }

    private static NightWindow window(List<float[]> vectors, Instant[] createdAt) {
        int n = vectors.size();
        var texts = new String[n];
        var rows = new float[n * 2];
        for (int i = 0; i < n; i++) {
            texts[i] = "t" + i;
            System.arraycopy(vectors.get(i), 0, rows, i * 2, 2);
        }
        return NightWindow.builder().texts(texts).vectors(rows).createdAt(createdAt)
                .from(Instant.parse("2026-09-20T00:00:00Z")).night(NIGHT_START).dimensions(2).build();
    }
}
