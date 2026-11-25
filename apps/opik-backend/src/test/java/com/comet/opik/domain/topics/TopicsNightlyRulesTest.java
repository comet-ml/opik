package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The nightly update's rules on small synthetic inputs: each step alone, then whole nights. */
@DisplayName("topics nightly update: rules")
class TopicsNightlyRulesTest {

    private static final AlgorithmConfig CONFIG = TopicsTestAlgorithm.config();
    private static final float[] EAST = {1, 0};
    private static final float[] SOUTH = {0, -1};
    private static final float[] WEST = {-1, 0};

    private final LocalDate discoveredOn = LocalDate.now(ZoneOffset.UTC)
            .minusDays(RandomUtils.secure().randomInt(20, 365));
    private final LocalDate night = discoveredOn.plusDays(RandomUtils.secure().randomInt(1, 30));
    private final Instant nightStart = night.atStartOfDay(ZoneOffset.UTC).toInstant();
    /** Inside the night's recent days. */
    private final Instant recentDay = nightStart.minus(Duration.ofHours(
            RandomUtils.secure().randomLong(1, CONFIG.getRecentDays() * 24L)));
    /** In the window, before its recent days. */
    private final Instant oldDay = nightStart.minus(Duration.ofDays(CONFIG.getRecentDays() + 1L));

    @Nested
    @DisplayName("lifecycle")
    class Lifecycles {

        @Test
        @DisplayName("a third low night retires, a good night resets the streak")
        void lowShare() {
            var state = state(
                    topic().lowNights(CONFIG.getRetireNights() - 1).build(),
                    topic().lowNights(CONFIG.getRetireNights() - 1).build(),
                    topic().build());
            var labels = new int[300];
            Arrays.fill(labels, 2);
            labels[0] = 0;
            labels[1] = 1;
            labels[2] = 1;

            state.retireLowShare(labels, CONFIG.getRetireShare(), CONFIG.getRetireNights());

            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(state.topic(1).getLowNights()).isZero();
            assertThat(state.live()).containsExactly(1, 2);
        }

        @ParameterizedTest(name = "{0} held traces: retires on night {1}")
        @CsvSource({"0, 1", "1, 3"})
        @DisplayName("a topic that holds no trace retires the same night; a small one that holds a trace needs three "
                + "low nights")
        void lowNightsByHeldTraces(int held, int nights) {
            var state = state(topic().build(), topic().build());
            var labels = new int[300];
            Arrays.fill(labels, 1);
            Arrays.fill(labels, 0, held, 0);

            for (int night = 1; night < nights; night++) {
                state.retireLowShare(labels, CONFIG.getRetireShare(), CONFIG.getRetireNights());

                assertThat(state.topic(0).getLowNights()).isEqualTo(night);
                assertThat(state.live()).containsExactly(0, 1);
            }

            state.retireLowShare(labels, CONFIG.getRetireShare(), CONFIG.getRetireNights());

            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(state.live()).containsExactly(1);
        }

        @Test
        @DisplayName("a topic born after the discovery run is measured on the whole window, as any other")
        void newborn() {
            var state = state(topic().build(), topic().born(night.minusDays(2)).build());
            var labels = new int[300];
            labels[299] = 1;

            state.retireLowShare(labels, CONFIG.getRetireShare(), CONFIG.getRetireNights());

            assertThat(state.topic(1).getLowNights()).isEqualTo(1);
            assertThat(state.topic(0).getLowNights()).isZero();
        }

        @Test
        @DisplayName("a live topic's size is its count in the labels")
        void sizes() {
            int busy = RandomUtils.secure().randomInt(1, 100);
            var state = state(topic().size(RandomUtils.secure().randomInt(1, 100)).build(), topic().build());
            var labels = new int[busy];
            Arrays.fill(labels, 1);

            state.recordSizes(labels);

            assertThat(state.topic(0).getSize()).isZero();
            assertThat(state.topic(1).getSize()).isEqualTo(busy);
        }
    }

    @Nested
    @DisplayName("candidates")
    class Candidates {

        @Test
        @DisplayName("a group's share and recent share are counted over the whole window, not the Other traces")
        void sharesOfWholeWindow() {
            // 60 East traces have an owner; 20 South and 20 West are Other. Recent: 40 East, 10 South, 20 West.
            var vectors = three(60, 20, 20);
            var createdAt = new Instant[100];
            for (int i = 0; i < createdAt.length; i++) {
                boolean recent = i < 40 || (i >= 60 && i < 70) || i >= 80;
                createdAt[i] = recent ? recentDay : oldDay;
            }
            var window = window(vectors, createdAt);
            var unassigned = IntStream.range(60, 100).toArray();

            var grouping = CandidateGrouper.group(window, unassigned, 1, CONFIG);

            var labels = grouping.labels();
            var south = NightGroup.builder().group(labels[0]).size(20).share(0.2).recentShare(10.0 / 70).tightness(1)
                    .examples(List.of(window.texts(IntStream.range(60, 65).toArray()))).build();
            var west = NightGroup.builder().group(labels[39]).size(20).share(0.2).recentShare(20.0 / 70).tightness(1)
                    .examples(List.of(window.texts(IntStream.range(80, 85).toArray()))).build();
            assertThat(grouping.candidates()).containsExactlyInAnyOrder(south, west);
        }
    }

    @Nested
    @DisplayName("churn")
    class Churns {

        private static final int OTHER = TopicAlgorithm.OTHER;

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

    private NightState state(NightTopic... topics) {
        return NightState.builder()
                .topics(List.of(topics))
                .otherPrototypes(List.of())
                .rebuilds(RandomUtils.secure().randomInt(0, 100))
                .night(night)
                .ids(TopicsTestAlgorithm.ID_GENERATOR::generateId)
                .build();
    }

    /** A topic born on the discovery run, with a random id, name and definition and no prototypes. */
    private NightTopic.NightTopicBuilder topic() {
        return NightTopic.builder()
                .id(TopicsTestAlgorithm.ID_GENERATOR.generateId())
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .definition(RandomStringUtils.secure().nextAlphanumeric(30))
                .born(discoveredOn)
                .prototypes(List.of());
    }

    /** {@code east} traces on EAST, then {@code south} on SOUTH, then {@code west} on WEST. */
    private static List<float[]> three(int east, int south, int west) {
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < east + south + west; i++) {
            vectors.add(i < east ? EAST : i < east + south ? SOUTH : WEST);
        }
        return vectors;
    }

    /** One window trace per vector, each with its own random text. */
    private NightWindow window(List<float[]> vectors, Instant[] createdAt) {
        int n = vectors.size();
        var texts = new String[n];
        var rows = new float[n * 2];
        for (int i = 0; i < n; i++) {
            texts[i] = UUID.randomUUID() + " " + RandomStringUtils.secure().nextAlphanumeric(10);
            System.arraycopy(vectors.get(i), 0, rows, i * 2, 2);
        }
        return NightWindow.builder().texts(texts).vectors(rows).createdAt(createdAt)
                .from(nightStart.minus(Duration.ofDays(30))).night(nightStart).dimensions(2).build();
    }
}
