package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import com.comet.opik.domain.topics.NightState.RetiredReason;
import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The nightly update's rules on small synthetic inputs: each step alone, then whole nights. */
@DisplayName("topics nightly update: rules")
class TopicsNightlyRulesTest {

    private static final LocalDate FIRST_BUILD = LocalDate.parse("2026-10-01");
    private static final LocalDate NIGHT = LocalDate.parse("2026-10-20");
    private static final Instant NIGHT_START = Instant.parse("2026-10-20T00:00:00Z");
    /** Inside the night's last 7 days. */
    private static final Instant WINDOW_DAY = Instant.parse("2026-10-15T00:00:00Z");
    private static final float[] EAST = {1, 0};
    private static final float[] NORTH = {0, 1};
    private static final float[] SOUTH = {0, -1};
    private static final float[] WEST = {-1, 0};
    private static final NightLlm NO_LLM = (tag, prompt, schema, maxTokens) -> {
        throw new AssertionError("unexpected Qwen call '%s'".formatted(tag));
    };

    @Nested
    @DisplayName("verify")
    class Verify {

        private final List<TopicPrompts.VerifyPair> pairs = List.of(
                new TopicPrompts.VerifyPair(prompt(1, 0, 0.1), group(3, 50, 0.9)),
                new TopicPrompts.VerifyPair(prompt(1, 0, 0.1), group(1, 30, 0.9)));

        @Test
        @DisplayName("one call for every pair; a missing or out-of-range pair is not verified")
        void answers() {
            var verdicts = Verifier.ask("verify_naming", pairs, (tag, prompt, schema, maxTokens) -> {
                assertThat(tag).isEqualTo("verify_naming");
                assertThat(maxTokens).isEqualTo(60 + 25 * 2);
                assertThat(schema).isEqualTo(TopicPrompts.VERIFY_SCHEMA);
                assertThat(prompt).contains("PAIR 0", "PAIR 1").doesNotContain("PAIR 2");
                return json("""
                        {"answers": [{"pair": 0, "same": false}, {"pair": 2, "same": true}]}
                        """);
            });

            assertThat(verdicts).containsOnly(Map.entry(0, false));
        }

        @Test
        @DisplayName("a failed call fails the run: read as 'no pair verified', it would turn sound assigns into new topics")
        void failed() {
            assertThatThrownBy(() -> Verifier.ask("verify_naming", pairs, (tag, prompt, schema, maxTokens) -> {
                throw new TopicsRunException(Code.QWEN_TIMEOUT, "timeout");
            })).isInstanceOfSatisfying(TopicsRunException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_TIMEOUT));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {"missing|", "null|, \"same\": null", "a string|, \"same\": \"true\"",
                "a number|, \"same\": 1"})
        @DisplayName("a same that is missing or not a boolean does not verify the pair")
        void sameNotBoolean(String label, String same) {
            var verdicts = Verifier.ask("verify_naming", pairs, (tag, prompt, schema, maxTokens) -> json(
                    "{\"answers\": [{\"pair\": 0" + (same == null ? "" : same) + "}, {\"pair\": 1, \"same\": true}]}"));

            assertThat(verdicts).containsOnly(Map.entry(1, true));
        }
    }

    @Nested
    @DisplayName("naming")
    class Forced {

        @Test
        @DisplayName("a tight candidate gets a topic, a loose one is known Other without a call")
        void outcomes() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95), group(1, 50, 0.79)), topics(state), map, 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [0]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(1, ForcedBirth.Result.OTHER_LOOSE, null),
                    new ForcedBirth.Outcome(0, ForcedBirth.Result.BORN, 1));
            assertThat(map).containsOnly(Map.entry(0, 1), Map.entry(1, NightlyUpdate.OTHER));
            assertThat(state.topic(1).getName()).isEqualTo("Weather");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "a catch-all name|{\"name\": \"General Requests\", \"definition\": \"x\", \"clusters\": [0]}",
                "an empty name|{\"name\": \"\", \"definition\": \"x\", \"clusters\": [0]}",
                "a blank name|{\"name\": \" \\u00A0 \", \"definition\": \"x\", \"clusters\": [0]}",
                "an unknown cluster|{\"name\": \"Weather\", \"definition\": \"x\", \"clusters\": [7]}",
                "no new topic|"})
        @DisplayName("an answer that gives a group no usable topic sends it to known Other")
        void noName(String label, String entry) {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95)), topics(state), map, 10,
                    named(Map.of("naming", newTopics(entry == null ? "" : entry))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.OTHER_NAME, null));
            assertThat(map).containsOnly(Map.entry(0, NightlyUpdate.OTHER));
            assertThat(state.liveCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a name with a catch-all word inside is born, as in the first build")
        void catchAllWordInside() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95)), topics(state), map, 10,
                    named(Map.of("naming", newTopics(
                            "{\"name\": \"General Job Search\", \"definition\": \"F.\", \"clusters\": [0]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.BORN, 1));
            assertThat(state.topic(1).getName()).isEqualTo("General Job Search");
        }

        @Test
        @DisplayName("an answer that is not an object leaves every group in known Other")
        void unreadable() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95)), topics(state), map, 10,
                    named(Map.of("naming", "\"Sorry\"")));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.OTHER_NAME, null));
        }

        @Test
        @DisplayName("same-kind groups form one topic, a repeated group counts once, equal new names merge")
        void grouped() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95), group(1, 50, 0.95), group(2, 40, 0.95)),
                    topics(state), map, 10,
                    named(Map.of("naming", newTopics(
                            "{\"name\": \"Tire Search\", \"definition\": \"F.\", \"clusters\": [1, 0, 1]}, "
                                    + "{\"name\": \"tire  search\", \"definition\": \"G.\", \"clusters\": [0, 2]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(1, ForcedBirth.Result.BORN, 1),
                    new ForcedBirth.Outcome(0, ForcedBirth.Result.BORN, 1),
                    new ForcedBirth.Outcome(2, ForcedBirth.Result.BORN, 1));
            assertThat(state.liveCount()).isEqualTo(2);
            assertThat(state.topic(1).getName()).isEqualTo("Tire Search");
            assertThat(state.topic(1).getDefinition()).isEqualTo("F.");
        }

        @Test
        @DisplayName("a new name equal to a live topic's joins that topic")
        void sameName() {
            var state = state(0, topic("Tire Search"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95)), topics(state), map, 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \" TIRE search\", \"definition\": \"F.\", \"clusters\": [0]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.SAME_NAME, 0));
            assertThat(map).containsOnly(Map.entry(0, 0));
            assertThat(state.liveCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a verified assign joins the topic; a rejected one goes to the second, new-only call")
        void assigns() {
            var state = state(0, topic("A"), topic("B"));
            var map = new LinkedHashMap<Integer, Integer>();
            var tags = new ArrayList<String>();
            var llm = named(Map.of(
                    "naming", "{\"assign\": [{\"cluster\": 0, \"topic\": 2}, {\"cluster\": 1, \"topic\": 1},"
                            + " {\"cluster\": 1, \"topic\": 2}, {\"cluster\": 2, \"topic\": 9}], \"new\": [], \"other\": []}",
                    "verify_naming", "{\"answers\": [{\"pair\": 0, \"same\": true}, {\"pair\": 1, \"same\": false}]}",
                    "naming_new", newTopics("{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [1]}")),
                    tags);
            var outcomes = force(state, List.of(group(0, 60, 0.95), group(1, 50, 0.95), group(2, 40, 0.95)),
                    topics(state), map, 10, llm);

            assertThat(tags).containsExactly("naming", "verify_naming", "naming_new");
            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.ASSIGNED, 1),
                    new ForcedBirth.Outcome(1, ForcedBirth.Result.BORN, 2),
                    new ForcedBirth.Outcome(2, ForcedBirth.Result.OTHER_NAME, null));
            assertThat(map).containsOnly(Map.entry(0, 1), Map.entry(1, 2), Map.entry(2, NightlyUpdate.OTHER));
        }

        @Test
        @DisplayName("on a full list the lowest window share is replaced if below the new topic's, never one born tonight, "
                + "whatever the 7-day shares")
        void fullList() {
            var state = state(0, topic("A"), topic("B"));
            var topics = new LinkedHashMap<Integer, TopicPrompts.PromptTopic>();
            topics.put(0, share(1, 0, 0.30, 0.01));
            topics.put(1, share(2, 1, 0.10, 0.50));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state,
                    List.of(sized(0, 60, 0.95, 0.20), sized(1, 50, 0.95, 0.40), sized(2, 40, 0.95, 0.05)),
                    topics, map, 2,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [2]}, "
                                    + "{\"name\": \"Rain\", \"definition\": \"F.\", \"clusters\": [1]}, "
                                    + "{\"name\": \"Snow\", \"definition\": \"F.\", \"clusters\": [0]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.BORN, 2),
                    new ForcedBirth.Outcome(1, ForcedBirth.Result.BORN, 3),
                    new ForcedBirth.Outcome(2, ForcedBirth.Result.OTHER_FULL, null));
            assertThat(state.topic(1).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
            assertThat(state.live()).containsExactly(2, 3);
        }

        @Test
        @DisplayName("a new topic under 2% of the window stays in Other, one at the bar is born")
        void smallNew() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(sized(1, 60, 0.95, 0.03), sized(0, 40, 0.95, 0.01)), topics(state),
                    map, 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Big\", \"definition\": \"F.\", \"clusters\": [1]}, "
                                    + "{\"name\": \"Small\", \"definition\": \"F.\", \"clusters\": [0]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(1, ForcedBirth.Result.BORN, 1),
                    new ForcedBirth.Outcome(0, ForcedBirth.Result.OTHER_SMALL, null));
            assertThat(map).containsExactly(Map.entry(1, 1), Map.entry(0, NightlyUpdate.OTHER));
        }

        @Test
        @DisplayName("a new topic's share is summed as Python does: 0.1 + 0.2 + 0.3 is above 0.6")
        void pythonSum() {
            var state = state(0, topic("A"));
            var topics = new LinkedHashMap<Integer, TopicPrompts.PromptTopic>();
            topics.put(0, share(1, 0, 0.6, 0.6));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state,
                    List.of(sized(0, 60, 0.95, 0.1), sized(1, 50, 0.95, 0.2), sized(2, 40, 0.95, 0.3)),
                    topics, map, 1,
                    named(Map.of("naming", newTopics(
                            "{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [0, 1, 2]}"))));

            assertThat(outcomes).extracting(ForcedBirth.Outcome::result).containsOnly(ForcedBirth.Result.BORN);
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
        }

        @Test
        @DisplayName("the prompt shows the live topics and the groups largest first")
        void namingPrompt() {
            var state = state(0, topic("A"));
            var prompts = new ArrayList<String>();
            force(state, List.of(group(0, 60, 0.95), group(1, 70, 0.95)), topics(state), new LinkedHashMap<>(), 10,
                    (tag, prompt, schema, maxTokens) -> {
                        prompts.add(prompt);
                        assertThat(schema).isEqualTo(TopicPrompts.NAMING_GROUP_SCHEMA);
                        assertThat(maxTokens).isEqualTo(2500);
                        return json("{}");
                    });

            assertThat(prompts).singleElement().satisfies(prompt -> {
                assertThat(prompt).contains("CURRENT TOPICS").contains("  topic 1: T0 — D0");
                assertThat(prompt.indexOf("  cluster 1:")).isLessThan(prompt.indexOf("  cluster 0:"));
            });
        }
    }

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

        @Test
        @DisplayName("a topic that holds no trace retires the same night")
        void noTrace() {
            var state = state(0, topic("Empty"), topic("Big"));
            var labels = new int[300];
            Arrays.fill(labels, 1);

            Lifecycle.retire(state, window(300, WINDOW_DAY), labels);

            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(state.live()).containsExactly(1);
        }

        @Test
        @DisplayName("a small topic that holds a trace still needs three low nights")
        void smallNeedsThreeNights() {
            var state = state(0, topic("Small"), topic("Big"));
            var labels = new int[300];
            Arrays.fill(labels, 1);
            labels[0] = 0;

            Lifecycle.retire(state, window(300, WINDOW_DAY), labels);
            Lifecycle.retire(state, window(300, WINDOW_DAY), labels);

            assertThat(state.topic(0).getLowNights()).isEqualTo(2);
            assertThat(state.live()).containsExactly(0, 1);

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
    @DisplayName("topic examples")
    class TopicExamples {

        @Test
        @DisplayName("with no repeated text, a topic's examples are its members nearest to a prototype, not the "
                + "alphabetically first")
        void nearest() {
            var examples = examples("b", 0.90, "d", 0.99, "a", 0.80, "c", 0.95, "e", 0.97, "f", 0.85);

            assertThat(examples).containsExactly("d", "e", "c", "b");
        }

        @Test
        @DisplayName("repeated texts come first, most frequent first; the free slots go to the nearest members")
        void someRepeats() {
            var examples = examples("x", 0.80, "a", 0.97, "y", 0.70, "x", 0.80, "b", 0.98, "y", 0.70, "y", 0.70,
                    "c", 0.99);

            assertThat(examples).containsExactly("y", "x", "c", "b");
        }

        @Test
        @DisplayName("with more repeated texts than slots, the most frequent fill them, equal counts by code point order")
        void moreRepeatsThanSlots() {
            var examples = examples("a", 0.99, "t", 0.5, "t", 0.5, "s", 0.5, "s", 0.5, "r", 0.5, "r", 0.5, "r", 0.5,
                    "q", 0.5, "q", 0.5, "q", 0.5, "q", 0.5);

            assertThat(examples).containsExactly("q", "r", "s", "t");
        }

        /** One live topic on EAST whose members are these (text, cosine to EAST) pairs, in trace order. */
        private List<String> examples(Object... members) {
            int n = members.length / 2;
            var texts = new String[n];
            var rows = new float[n * 2];
            for (int i = 0; i < n; i++) {
                texts[i] = (String) members[2 * i];
                double cosine = (double) members[2 * i + 1];
                rows[2 * i] = (float) cosine;
                rows[2 * i + 1] = (float) Math.sqrt(1 - cosine * cosine);
            }
            var createdAt = new Instant[n];
            Arrays.fill(createdAt, WINDOW_DAY);
            var window = NightWindow.builder().texts(texts).vectors(rows).createdAt(createdAt)
                    .from(Instant.parse("2026-09-20T00:00:00Z")).night(NIGHT_START).dimensions(2).build();
            var state = state(1, topic("East", EAST));

            var topics = NightlyUpdate.promptTopics(state, window, new int[n], new boolean[n]);

            return topics.getFirst().examples();
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
    @DisplayName("the whole night")
    class Night {

        @Test
        @DisplayName("without an Other group of 20 traces there is no Qwen call, and the memory stays")
        void quiet() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));

            var result = NightlyUpdate.run(state, window(three(25, 5, 0)), 10, NO_LLM);

            assertThat(result.groupMap()).isEmpty();
            assertThat(result.candidates()).isEmpty();
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 0).count()).isEqualTo(25);
            assertThat(state.topic(0).getPrototypes()).containsExactly(EAST);
            assertThat(state.getOtherPrototypes()).containsExactly(SOUTH);
        }

        @Test
        @DisplayName("a known-Other prototype that held no trace in the final assignment leaves the memory, one gated by "
                + "a topic's prototype included; tonight's labels stay")
        void memoryIdle() {
            // Within cosine 0.95 of East's prototype, so gated.
            float[] gated = {0.99f, 0.141f};
            // Cosine 0.71 to East's prototype, so Other; 0.80 to the gated one, which still holds none of them.
            float[] between = {0.7071f, 0.7071f};
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH, WEST, gated));
            var vectors = new ArrayList<>(three(25, 5, 0));
            for (int i = 0; i < 5; i++) {
                vectors.add(between);
            }
            var window = window(vectors);
            var before = NightlyUpdate.assignFinal(state, window).labels();

            var result = NightlyUpdate.run(state, window, 10, NO_LLM);

            assertThat(result.labels()).containsExactly(before);
            assertThat(Arrays.stream(result.labels()).filter(label -> label == PrototypeAssigner.OTHER).count())
                    .isEqualTo(10);
            assertThat(state.getOtherPrototypes()).containsExactly(SOUTH);
            assertThat(NightlyUpdate.assignFinal(state, window).labels()).containsExactly(before);
        }

        @Test
        @DisplayName("on the night a topic retires, the known-Other prototype it gated stays and holds its traces: "
                + "tonight's labels are those of a night without the prune")
        void memoryGatedOnRetireNight() {
            // Within cosine 0.95 of North's prototype: while North is live, it is gated and holds no trace.
            float[] gated = {0.28f, 0.96f};
            // Cosine 0.77 to East's prototype, 0.83 to the gated one, 0.64 to North's.
            float[] between = {0.766f, 0.6428f};
            var north = topic("North", NORTH).toBuilder().lowNights(2).build();
            var state = state(1, topic("East", EAST), north);
            state.setOtherPrototypes(List.of(gated));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 120; i++) {
                vectors.add(i < 100 ? EAST : between);
            }
            var window = window(vectors);

            var result = NightlyUpdate.run(state, window, 10, NO_LLM);

            // Without the prune: North retires, the gated prototype is open again and holds the 20 traces between.
            var expected = new int[120];
            Arrays.fill(expected, 100, 120, PrototypeAssigner.OTHER);
            assertThat(state.topic(1).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(result.labels()).containsExactly(expected);
            assertThat(state.getOtherPrototypes()).containsExactly(gated);
            assertThat(NightlyUpdate.assignFinal(state, window).labels()).containsExactly(expected);
        }

        @Test
        @DisplayName("a live topic's prototype that took no trace in the window is dropped; tonight's labels stay")
        void idlePrototype() {
            float[] near = {0.8f, 0.6f};
            var state = state(1, topic("East", EAST, near, WEST), topic("North", NORTH));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 40; i++) {
                vectors.add(i < 20 ? EAST : i < 30 ? near : NORTH);
            }
            var window = window(vectors);
            var before = NightlyUpdate.assignFinal(state, window).labels();

            var result = NightlyUpdate.run(state, window, 10, NO_LLM);

            assertThat(result.labels()).containsExactly(before);
            assertThat(state.topic(0).getPrototypes()).containsExactly(EAST, near);
            assertThat(state.topic(1).getPrototypes()).containsExactly(NORTH);
            assertThat(NightlyUpdate.assignFinal(state, window).labels()).containsExactly(before);
        }

        @Test
        @DisplayName("a live topic none of whose prototypes took a trace keeps its first")
        void idleTopicKeepsOne() {
            var state = state(1, topic("East", EAST), topic("North", NORTH, WEST));

            NightlyUpdate.dropIdle(state, NightlyUpdate.assignFinal(state, window(30, WINDOW_DAY)));

            assertThat(state.topic(0).getPrototypes()).containsExactly(EAST);
            assertThat(state.topic(1).getPrototypes()).containsExactly(NORTH);
        }

        @Test
        @DisplayName("a night on which every live topic retires saves an empty list: all traces are Other, and the "
                + "invariants hold")
        void allLiveRetire() {
            // The next night the job finds no live topic and runs a first build (TopicsNightlyService.modeOf): it saves
            // rebuilds 1 and a new first build night, and replaces every prototype, so these topics cannot come back.
            var east = topic("East", EAST).toBuilder().lowNights(2).build();
            var north = topic("North", NORTH).toBuilder().lowNights(2).build();
            var state = state(3, east, north);
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 15; i++) {
                vectors.add(SOUTH);
            }

            var result = NightlyUpdate.run(state, window(vectors), 10, NO_LLM);

            assertThat(state.liveCount()).isZero();
            assertThat(state.getTopics()).extracting(NightState.Topic::getRetiredReason)
                    .containsOnly(RetiredReason.LOW_SHARE);
            assertThat(result.labels()).containsOnly(PrototypeAssigner.OTHER);
            NightInvariants.check(state, 2);
        }

        @Test
        @DisplayName("the night's one assignment is the final one: a known-Other prototype near a topic's is gated")
        void sameAsFinal() {
            var state = state(1, topic("East", EAST), topic("North", NORTH));
            state.setOtherPrototypes(List.of(new float[]{0.99f, 0.141f}, SOUTH));
            var window = window(three(10, 10, 10));

            var owners = NightlyUpdate.assign(state, window, state.getOtherPrototypes());
            var labels = NightlyUpdate.assignFinal(state, window).labels();

            for (int i = 0; i < labels.length; i++) {
                assertThat(owners[i] >= 0 ? owners[i] : PrototypeAssigner.OTHER).isEqualTo(labels[i]);
            }
            assertThat(owners).contains(-11);
        }

        @Test
        @DisplayName("small groups reach the naming, which may attach them to a topic through the verify; "
                + "the group's centre becomes the topic's prototype")
        void attach() {
            var state = state(1, topic("East", EAST), topic("North", NORTH));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(twoSmall()), 10,
                    named(Map.of(
                            "naming", "{\"assign\": [{\"cluster\": 0, \"topic\": 2}, {\"cluster\": 1, \"topic\": 2}],"
                                    + " \"new\": [], \"other\": []}",
                            "verify_naming",
                            "{\"answers\": [{\"pair\": 0, \"same\": true}, {\"pair\": 1, \"same\": true}]}"),
                            tags));

            assertThat(tags).containsExactly("naming", "verify_naming");
            assertThat(result.big()).isEmpty();
            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsExactly(ForcedBirth.Result.ASSIGNED, ForcedBirth.Result.ASSIGNED);
            assertThat(state.topic(1).getPrototypes()).hasSize(3);
            assertThat(state.getOtherPrototypes()).isEmpty();
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 1).count()).isEqualTo(1065);
        }

        @Test
        @DisplayName("a group the verify rejected for a topic and the second call names after that topic is known "
                + "Other; the topic's prototypes stay")
        void sameNameAfterReject() {
            var state = state(1, topic("East", EAST), topic("North", NORTH));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(twoSmall()), 10,
                    named(Map.of(
                            "naming", "{\"assign\": [{\"cluster\": 0, \"topic\": 2}, {\"cluster\": 1, \"topic\": 2}],"
                                    + " \"new\": [], \"other\": []}",
                            "verify_naming",
                            "{\"answers\": [{\"pair\": 0, \"same\": false}, {\"pair\": 1, \"same\": false}]}",
                            "naming_new",
                            newTopics("{\"name\": \"north\", \"definition\": \"N.\", \"clusters\": [0, 1]}")),
                            tags));

            assertThat(tags).containsExactly("naming", "verify_naming", "naming_new");
            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsExactly(ForcedBirth.Result.OTHER_NAME, ForcedBirth.Result.OTHER_NAME);
            assertThat(result.groupMap()).containsOnly(Map.entry(0, NightlyUpdate.OTHER),
                    Map.entry(1, NightlyUpdate.OTHER));
            assertThat(state.topic(1).getPrototypes()).containsExactly(NORTH);
        }

        @Test
        @DisplayName("small groups the naming merges into one new topic are born when their window share reaches 2%")
        void merged() {
            var state = state(1, topic("East", EAST), topic("North", NORTH));

            var result = NightlyUpdate.run(state, window(twoSmall()), 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Both\", \"definition\": \"B.\", \"clusters\": [0, 1]}"))));

            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsExactly(ForcedBirth.Result.BORN, ForcedBirth.Result.BORN);
            assertThat(state.live()).containsExactly(0, 1, 2);
            assertThat(state.topic(2).getBorn()).isEqualTo(NIGHT);
            assertThat(state.topic(2).getPrototypes()).hasSize(2);
            assertThat(state.getOtherPrototypes()).isEmpty();
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 2).count()).isEqualTo(65);
        }

        @Test
        @DisplayName("a new topic under 2% of the window is not born; its group stays in Other and in the memory")
        void small() {
            var state = state(1, topic("East", EAST));

            var result = NightlyUpdate.run(state, window(three(1980, 20, 0)), 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"South\", \"definition\": \"S.\", \"clusters\": [0, 1]}"))));

            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsExactly(ForcedBirth.Result.OTHER_SMALL);
            assertThat(state.live()).containsExactly(0);
            assertThat(state.getOtherPrototypes()).hasSize(1);
            assertThat(state.getOtherPrototypes().get(0)).containsExactly(SOUTH, within(1e-5f));
            assertThat(Arrays.stream(result.labels()).filter(label -> label == PrototypeAssigner.OTHER).count())
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("the memory's traces are grouped again each night; a placed group drops the prototype that held it")
        void memoryPlaced() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(three(20, 40, 40)), 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Both\", \"definition\": \"B.\", \"clusters\": [0, 1]}")),
                            tags));

            assertThat(tags).containsExactly("naming");
            assertThat(result.unassigned()).isEqualTo(80);
            assertThat(state.getOtherPrototypes()).isEmpty();
            assertThat(state.topic(1).getPrototypes()).hasSize(2);
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 1).count()).isEqualTo(80);
        }

        @Test
        @DisplayName("a known-Other prototype more than half of whose held traces were placed tonight leaves the memory, "
                + "though the placed group's centre is outside the gate and it still holds the rest")
        void memoryMostlyPlaced() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));

            var result = NightlyUpdate.run(state, window(memoryHeld(30, 10)), 10, assignGroup(0));

            assertThat(result.forced()).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.ASSIGNED, 0));
            assertThat(state.topic(0).getPrototypes()).hasSize(2);
            assertThat(state.topic(0).getPrototypes().get(1)).containsExactly(PLACED, within(1e-5f));
            assertThat(state.getOtherPrototypes()).isEmpty();
        }

        @Test
        @DisplayName("a known-Other prototype exactly half of whose held traces were placed tonight stays in the memory")
        void memoryHalfPlaced() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));

            var result = NightlyUpdate.run(state, window(memoryHeld(20, 20)), 10, assignGroup(1));

            assertThat(result.forced()).contains(new ForcedBirth.Outcome(1, ForcedBirth.Result.ASSIGNED, 0));
            assertThat(state.topic(0).getPrototypes().get(1)).containsExactly(PLACED, within(1e-5f));
            assertThat(state.getOtherPrototypes()).singleElement().isSameAs(SOUTH);
        }

        @Test
        @DisplayName("a known-Other prototype less than half of whose held traces were placed tonight stays in the memory "
                + "and holds the rest in the final assignment")
        void memoryLessThanHalfPlaced() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));

            var result = NightlyUpdate.run(state, window(memoryHeld(20, 30)), 10, assignGroup(1));

            assertThat(result.forced()).contains(new ForcedBirth.Outcome(1, ForcedBirth.Result.ASSIGNED, 0));
            assertThat(state.topic(0).getPrototypes().get(1)).containsExactly(PLACED, within(1e-5f));
            assertThat(state.getOtherPrototypes()).singleElement().isSameAs(SOUTH);
            assertThat(Arrays.copyOfRange(result.labels(), 60, 90)).containsOnly(PrototypeAssigner.OTHER);
            NightlyUpdate.dropIdle(state, NightlyUpdate.assignFinal(state, window(memoryHeld(20, 30))));
            assertThat(state.getOtherPrototypes()).singleElement().isSameAs(SOUTH);
        }

        @Test
        @DisplayName("a group left in Other is kept in the memory once; a prototype that holds its traces is not kept twice")
        void memoryRefused() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));

            var result = NightlyUpdate.run(state, window(three(20, 40, 40)), 10,
                    named(Map.of("naming", newTopics(""))));

            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsOnly(ForcedBirth.Result.OTHER_NAME);
            assertThat(state.getOtherPrototypes()).hasSize(2);
            assertThat(state.getOtherPrototypes().get(0)).isSameAs(SOUTH);
            assertThat(state.getOtherPrototypes().get(1)).containsExactly(WEST, within(1e-5f));
            assertThat(Arrays.stream(result.labels()).filter(label -> label == PrototypeAssigner.OTHER).count())
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("on a full list a big group replaces the weakest topic by window share; the next one stays in Other")
        void fullList() {
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 100; i++) {
                vectors.add(i < 20 ? EAST : i < 60 ? NORTH : SOUTH);
            }
            var state = state(1, topic("East", EAST));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(vectors), 1,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [0]}, "
                                    + "{\"name\": \"Rain\", \"definition\": \"F.\", \"clusters\": [1]}")),
                            tags));

            assertThat(tags).containsExactly("naming");
            assertThat(result.big()).containsExactly(0, 1);
            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsExactly(ForcedBirth.Result.BORN, ForcedBirth.Result.OTHER_FULL);
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
            assertThat(state.live()).containsExactly(1);
            assertThat(state.getOtherPrototypes()).hasSize(1);
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 1).count()).isEqualTo(40);
        }

        @Test
        @DisplayName("on a full list a topic's share counts the group it got tonight: lifted above the new topic's, it "
                + "stays live and keeps the group; the new topic's group stays in Other")
        void fullListCountsTonightsGroup() {
            var state = state(1, topic("East", EAST));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(nearEastAndSouth(30, 25, 45)), 1, assignAndNew(tags));

            assertThat(tags).containsExactly("naming", "verify_naming");
            assertThat(result.forced()).containsExactly(new ForcedBirth.Outcome(1, ForcedBirth.Result.ASSIGNED, 0),
                    new ForcedBirth.Outcome(0, ForcedBirth.Result.OTHER_FULL, null));
            assertThat(state.live()).containsExactly(0);
            assertThat(state.topic(0).getPrototypes()).hasSize(2);
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 0).count()).isEqualTo(55);
        }

        @Test
        @DisplayName("on a full list a topic whose share with tonight's group is still below the new topic's is "
                + "replaced; the group it got is lost for the night: no prototype, not in the memory, its traces Other")
        void fullListReplacesDespiteTonightsGroup() {
            var state = state(1, topic("East", EAST));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(nearEastAndSouth(20, 25, 55)), 1, assignAndNew(tags));

            assertThat(tags).containsExactly("naming", "verify_naming");
            assertThat(result.forced()).containsExactly(new ForcedBirth.Outcome(1, ForcedBirth.Result.ASSIGNED, 0),
                    new ForcedBirth.Outcome(0, ForcedBirth.Result.BORN, 1));
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
            assertThat(state.topic(0).getPrototypes()).containsExactly(EAST);
            assertThat(state.live()).containsExactly(1);
            assertThat(state.getOtherPrototypes()).isEmpty();
            assertThat(Arrays.stream(result.labels()).filter(label -> label == PrototypeAssigner.OTHER).count())
                    .isEqualTo(45);
        }

        @Test
        @DisplayName("a topic's prototypes never move: a night without decisions leaves the list as it was")
        void frozen() {
            float[] tilted = {0.9f, 0.43589f};
            var state = state(1, topic("East", tilted));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 40; i++) {
                vectors.add(i < 30 ? EAST : new float[]{0.95f, 0.31225f});
            }

            var result = NightlyUpdate.run(state, window(vectors), 10, NO_LLM);

            assertThat(result.labels()).containsOnly(0);
            assertThat(state.topic(0).getPrototypes()).singleElement().isSameAs(tilted);
        }

        /** Cosine 0.8 to SOUTH and 0.6 to EAST. */
        private static final float[] PLACED = {0.6f, -0.8f};

        /**
         * 40 on EAST, then {@code placed} traces held by a SOUTH memory prototype at cosine 0.8 to it (0.6 to EAST, so
         * outside its 0.95 gate once their centre is a prototype of East), then {@code kept} on SOUTH.
         */
        private static List<float[]> memoryHeld(int placed, int kept) {
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 40 + placed + kept; i++) {
                vectors.add(i < 40 ? EAST : i < 40 + placed ? PLACED : SOUTH);
            }
            return vectors;
        }

        /** {@code group} to East through a verified assign; any other group is left out of the answer. */
        private static NightLlm assignGroup(int group) {
            return named(Map.of(
                    "naming", "{\"assign\": [{\"cluster\": " + group + ", \"topic\": 1}], \"new\": [], \"other\": []}",
                    "verify_naming", "{\"answers\": [{\"pair\": 0, \"same\": true}]}"));
        }

        /** {@code east} on EAST, then {@code near} Other traces at cosine 0.6 to EAST, then {@code south} on SOUTH. */
        private static List<float[]> nearEastAndSouth(int east, int near, int south) {
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < east + near + south; i++) {
                vectors.add(i < east ? EAST : i < east + near ? new float[]{0.6f, 0.8f} : SOUTH);
            }
            return vectors;
        }

        /** Group 1 (near East) to East through a verified assign, group 0 (South, the larger) as a new topic. */
        private static NightLlm assignAndNew(List<String> tags) {
            return named(Map.of(
                    "naming", "{\"assign\": [{\"cluster\": 1, \"topic\": 1}], \"new\": [{\"name\": \"South\","
                            + " \"definition\": \"S.\", \"clusters\": [0]}], \"other\": []}",
                    "verify_naming", "{\"answers\": [{\"pair\": 0, \"same\": true}]}"),
                    tags);
        }

        /** 1000 on EAST, 1000 on NORTH, then two small tight Other groups: 35 near NORTH and 30 on SOUTH. */
        private static List<float[]> twoSmall() {
            var near = new float[]{0.68f, 0.7332f};
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 2065; i++) {
                vectors.add(i < 1000 ? EAST : i < 2000 ? NORTH : i < 2035 ? near : SOUTH);
            }
            return vectors;
        }
    }

    @Nested
    @DisplayName("churn")
    class Churns {

        private static final int OTHER = PrototypeAssigner.OTHER;

        @Test
        @DisplayName("churn counts topic-to-topic moves, churn_any every move, over every window trace")
        void churn() {
            var result = TopicChurn.of(new int[]{0, 0, 1, OTHER}, new int[]{0, 1, OTHER, 0});

            assertThat(result.churn()).isEqualTo(0.5);
            assertThat(result.churnAny()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("no trace in a topic both before and after is churn 0")
        void noTopicOnBothSides() {
            assertThat(TopicChurn.of(new int[]{OTHER}, new int[]{0})).isEqualTo(new TopicChurn.Churn(0, 1));
        }

        @Test
        @DisplayName("a whole night: the trace of a topic retired tonight has changed")
        void wholeNight() {
            var east = topic("East", EAST).toBuilder().lowNights(2).build();
            var state = state(1, east, topic("North", NORTH));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 300; i++) {
                vectors.add(i < 1 ? EAST : NORTH);
            }

            var result = NightlyUpdate.run(state, window(vectors), 10, NO_LLM);
            var churn = TopicChurn.of(result.before(), result.labels());

            assertThat(result.before()[0]).isZero();
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(result.labels()[0]).isEqualTo(OTHER);
            assertThat(churn.churnAny()).isEqualTo(1.0 / 300);
            assertThat(churn.churn()).isZero();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("a valid night passes")
        void valid() {
            var state = state(0, topic("A", EAST));
            state.born("B", "B def");
            state.topic(1).setPrototypes(List.of(NORTH));

            NightInvariants.check(state, 2);
        }

        @Test
        @DisplayName("two live topics with one name or a long prototype fail the run")
        void others() {
            var twins = state(0, topic("Billing", EAST), topic("  BILLING ", NORTH));
            assertInvariant(() -> NightInvariants.check(twins, 2), "have one name");

            var long_ = state(0, topic("A", new float[]{1, 1}));
            assertInvariant(() -> NightInvariants.check(long_, 2), "norm");
        }

        @Test
        @DisplayName("a prototype with a NaN value fails the run")
        void nan() {
            var state = state(0, topic("A", new float[]{Float.NaN, 0}));

            assertInvariant(() -> NightInvariants.check(state, 2), "norm 'NaN'");
        }

        private static void assertInvariant(Runnable check, String message) {
            assertThatThrownBy(check::run).isInstanceOfSatisfying(TopicsRunException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo(Code.INVARIANT);
                assertThat(exception.getMessage()).contains(message);
            });
        }
    }

    @Nested
    @DisplayName("answer parse")
    class AnswerParse {

        @Test
        @DisplayName("an object passes, anything else gives none, a text answer that holds the object included")
        void object() throws IOException {
            var object = InferenceJson.MAPPER.readTree("{\"assign\": [], \"new\": []}");
            assertThat(NightJson.object(object)).isSameAs(object);
            assertThat(NightJson.object(InferenceJson.MAPPER.readTree(
                    "\"Here it is: {\\\"assign\\\": [], \\\"new\\\": []} done\""))).isNull();
            assertThat(NightJson.object(InferenceJson.MAPPER.readTree("\"Sorry\""))).isNull();
            assertThat(NightJson.object(InferenceJson.MAPPER.readTree("\"{not json}\""))).isNull();
            assertThat(NightJson.object(InferenceJson.MAPPER.readTree("\"[1, 2]\""))).isNull();
            assertThat(NightJson.object(InferenceJson.MAPPER.readTree("[1, 2]"))).isNull();
            assertThat(NightJson.object(null)).isNull();
        }
    }

    @Nested
    @DisplayName("prompts")
    class Prompts {

        @Test
        @DisplayName("a placeholder inside trace text stays as it is: the groups are shown once")
        void placeholderInText() {
            var base = prompt(1, 0, 0.25);
            var topic = new TopicPrompts.PromptTopic(1, 0, base.name(), "Asks about {candidates} and {clusters}",
                    base.share(), base.recentShare(), base.examples());
            var groups = List.of(group(0, 40, 0.9));

            var naming = TopicPrompts.namingGroup(List.of(topic), groups, true);

            assertThat(naming).contains("Asks about {candidates} and {clusters}");
            assertThat(naming.split("cluster 0:", -1)).hasSize(2);
            assertThat(TopicPrompts.namingGroup(List.of(), groups, false)).contains("  (none)")
                    .doesNotContain("{topics}", "{clusters}");
        }

        @Test
        @DisplayName("verify shows the larger of the two shares of the new cluster")
        void verify() {
            var prompt = TopicPrompts.verify(List.of(new TopicPrompts.VerifyPair(prompt(1, 0, 0.25),
                    group(0, 40, 0.9, 0.031))));

            assertThat(prompt).contains("NEW CLUSTER (share 10.0%)");
        }
    }

    @Nested
    @DisplayName("a failed naming call")
    class FailedNaming {

        @Test
        @DisplayName("fails the run, so a full list's weakest topic is not retired for nothing")
        void failsTheRun() {
            var live = new ArrayList<NightState.Topic>();
            for (int i = 0; i < 10; i++) {
                live.add(topic("T" + i));
            }
            var state = state(0, live.toArray(NightState.Topic[]::new));
            var map = new LinkedHashMap<Integer, Integer>();
            NightLlm failing = (tag, prompt, schema, maxTokens) -> {
                throw new TopicsRunException(Code.QWEN_ERROR, "down");
            };

            assertThatThrownBy(() -> force(state, List.of(group(0, 60, 0.95, 0.9)), topics(state), map, 10, failing))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_ERROR));
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

    private static List<TopicPrompts.PromptTopic> shown(NightState state) {
        var out = new ArrayList<TopicPrompts.PromptTopic>();
        var live = state.live();
        for (int p = 0; p < live.size(); p++) {
            out.add(prompt(p + 1, live.get(p), 0.1));
        }
        return out;
    }

    private static Map<Integer, TopicPrompts.PromptTopic> topics(NightState state) {
        var out = new LinkedHashMap<Integer, TopicPrompts.PromptTopic>();
        shown(state).forEach(topic -> out.put(topic.topic(), topic));
        return out;
    }

    private static TopicPrompts.PromptTopic share(int id, int topic, double share, double recentShare) {
        var base = prompt(id, topic, recentShare);
        return new TopicPrompts.PromptTopic(id, topic, base.name(), base.definition(), share, recentShare,
                base.examples());
    }

    private static TopicPrompts.PromptTopic prompt(int id, int topic, double recentShare) {
        return TopicPrompts.PromptTopic.builder()
                .id(id)
                .topic(topic)
                .name("T" + topic)
                .definition("D" + topic)
                .share(0.1)
                .recentShare(recentShare)
                .examples(List.of("example"))
                .build();
    }

    private static GroupSummary group(int group, int size, double tightness) {
        return group(group, size, tightness, 0.1);
    }

    private static GroupSummary group(int group, int size, double tightness, double recentShare) {
        return new GroupSummary(group, size, 0.1, recentShare, tightness, List.of("a", "b"));
    }

    /** A group with this share of the window. */
    private static GroupSummary sized(int group, int size, double tightness, double share) {
        return new GroupSummary(group, size, share, share, tightness, List.of("a", "b"));
    }

    private static List<ForcedBirth.Outcome> force(NightState state, List<GroupSummary> ordered,
            Map<Integer, TopicPrompts.PromptTopic> topics, Map<Integer, Integer> map, int maxTopics, NightLlm llm) {
        return ForcedBirth.apply(state, ordered, map, () -> List.copyOf(topics.values()), maxTopics, llm);
    }

    /** A grouped naming answer with these {@code new} entries and no assign. */
    private static String newTopics(String entries) {
        return "{\"assign\": [], \"new\": [" + entries + "], \"other\": []}";
    }

    /** A Qwen stub that answers each tag with its JSON. */
    private static NightLlm named(Map<String, String> answers) {
        return named(answers, new ArrayList<>());
    }

    private static NightLlm named(Map<String, String> answers, List<String> tags) {
        return (tag, prompt, schema, maxTokens) -> {
            tags.add(tag);
            assertThat(answers).containsKey(tag);
            return json(answers.get(tag));
        };
    }

    /** {@code east} traces on EAST, then {@code south} on SOUTH, then {@code west} on WEST. */
    private static List<float[]> three(int east, int south, int west) {
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < east + south + west; i++) {
            vectors.add(i < east ? EAST : i < east + south ? SOUTH : WEST);
        }
        return vectors;
    }

    private static List<float[]> directions(int east, int north) {
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < east + north; i++) {
            vectors.add(i < east ? EAST : NORTH);
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

    private static NightWindow window(List<float[]> vectors) {
        var createdAt = new Instant[vectors.size()];
        Arrays.fill(createdAt, WINDOW_DAY);
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

    private static JsonNode json(String text) {
        try {
            return InferenceJson.MAPPER.readTree(text);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
