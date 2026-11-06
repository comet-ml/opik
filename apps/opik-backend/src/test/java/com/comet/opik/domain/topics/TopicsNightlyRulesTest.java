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
        @DisplayName("one call for every pair; a missing or out-of-range pair is not verified; same follows Python truth")
        void answers() {
            var verdicts = Verifier.ask("verify_naming", pairs, (tag, prompt, schema, maxTokens) -> {
                assertThat(tag).isEqualTo("verify_naming");
                assertThat(maxTokens).isEqualTo(60 + 25 * 2);
                assertThat(schema).isEqualTo(TopicPrompts.VERIFY_SCHEMA);
                assertThat(prompt).contains("PAIR 0", "PAIR 1").doesNotContain("PAIR 2");
                return json("""
                        {"answers": [{"pair": 0, "same": ""}, {"pair": 1}, {"pair": 2, "same": true}]}
                        """);
            });

            assertThat(verdicts).containsOnly(Map.entry(0, false), Map.entry(1, true));
        }

        @Test
        @DisplayName("a failed call fails the run: read as 'no pair verified', it would turn sound assigns into new topics")
        void failed() {
            assertThatThrownBy(() -> Verifier.ask("verify_naming", pairs, (tag, prompt, schema, maxTokens) -> {
                throw new TopicsRunException(Code.QWEN_TIMEOUT, "timeout");
            })).isInstanceOfSatisfying(TopicsRunException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_TIMEOUT));
        }

        @Test
        @DisplayName("an unreadable answer verifies nothing and the night goes on")
        void unreadable() {
            var verdicts = Verifier.ask("verify_naming", pairs, (tag, prompt, schema, maxTokens) -> {
                throw new IllegalStateException("unreadable");
            });

            assertThat(verdicts).isNull();
        }

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource(delimiter = '|', value = {
                "true|true", "false|false", "0|false", "0.5|true", "\"\"|false", "\"no\"|true", "[]|false",
                "[0]|true", "{}|false", "null|false"})
        @DisplayName("truthy is Python's bool")
        void truthy(String value, boolean expected) {
            assertThat(Verifier.truthy(json(value))).isEqualTo(expected);
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
                "a catch-all name|{\"name\": \"General Help\", \"definition\": \"x\", \"clusters\": [0]}",
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
    @DisplayName("rebirth")
    class Rebirths {

        @Test
        @DisplayName("a topic born on a dormant topic's traces takes its id, name and born, with the new prototypes")
        void takesIdentity() {
            var state = state(0, topic("East", EAST), dormant("North", RetiredReason.LOW_SHARE, NORTH));
            var id = state.topic(1).getId();
            var close = new float[]{0.6f, 0.8f};
            int child = state.born("Northern Again", "new def");
            state.topic(child).setPrototypes(List.of(close));

            var moves = Rebirth.apply(state, window(directions(90, 10)));

            assertThat(moves).singleElement().isEqualTo(new int[]{2, 1});
            assertThat(state.getTopics()).hasSize(2);
            var topic = state.topic(1);
            assertThat(topic.isLive()).isTrue();
            assertThat(topic.getId()).isEqualTo(id);
            assertThat(topic.getName()).isEqualTo("North");
            assertThat(topic.getDefinition()).isEqualTo("North def");
            assertThat(topic.getBorn()).isEqualTo(FIRST_BUILD);
            assertThat(topic.getPrototypes()).containsExactly(close);
            assertThat(topic.getRevivedAt()).isEqualTo(NIGHT);
            assertThat(topic.getRetiredReason()).isNull();
            assertThat(topic.getLowNights()).isZero();
            assertThat(topic.getInactiveSince()).isNull();
        }

        @Test
        @DisplayName("a newborn stays new when its traces are not the dormant topic's, too few, or the topic is not dormant")
        void staysNew() {
            var far = state(0, topic("East", EAST), dormant("Near", RetiredReason.CAP, new float[]{0.8f, 0.6f}));
            far.topic(far.born("North", "def")).setPrototypes(List.of(NORTH));
            assertThat(Rebirth.apply(far, window(directions(50, 50)))).isEmpty();
            assertThat(far.getTopics()).hasSize(3);

            var few = state(0, topic("East", EAST), dormant("North", RetiredReason.REPLACED, NORTH));
            few.topic(few.born("North Again", "def")).setPrototypes(List.of(NORTH));
            assertThat(Rebirth.apply(few, window(directions(96, 4)))).isEmpty();

            for (var reason : List.of(RetiredReason.MERGED, RetiredReason.REVIEW)) {
                var judged = state(0, topic("East", EAST), dormant("North", reason, NORTH));
                judged.topic(judged.born("North Again", "def")).setPrototypes(List.of(NORTH));
                assertThat(judged.dormant()).isEmpty();
                assertThat(Rebirth.apply(judged, window(directions(50, 50)))).isEmpty();
            }

            var tonight = state(0, topic("East", EAST), topic("North", NORTH));
            tonight.retire(1, RetiredReason.LOW_SHARE);
            assertThat(tonight.dormant()).isEmpty();
        }

        @Test
        @DisplayName("half of the newborn's traces must be the dormant topic's, and each dormant topic comes back once")
        void halfAndOnce() {
            var west = new float[]{-0.8f, 0.6f};
            var half = state(0, topic("East", EAST), dormant("North", RetiredReason.LOW_SHARE, NORTH));
            half.topic(half.born("Wide", "def")).setPrototypes(List.of(new float[]{-0.28f, 0.96f}));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 30; i++) {
                vectors.add(i < 10 ? EAST : i < 19 ? NORTH : west);
            }
            assertThat(Rebirth.apply(half, window(vectors))).isEmpty();

            var twice = state(0, topic("East", EAST), dormant("North", RetiredReason.LOW_SHARE, NORTH));
            twice.topic(twice.born("North A", "def")).setPrototypes(List.of(NORTH));
            twice.topic(twice.born("North B", "def")).setPrototypes(List.of(new float[]{0.6f, 0.8f}));
            var between = new float[]{0.6f, 0.8f};
            var mixed = new ArrayList<>(directions(40, 30));
            for (int i = 0; i < 30; i++) {
                mixed.add(between);
            }
            var moves = Rebirth.apply(twice, window(mixed));
            assertThat(moves).singleElement().isEqualTo(new int[]{2, 1});
            assertThat(twice.getTopics()).extracting(NightState.Topic::getName)
                    .containsExactly("East", "North", "North B");
        }

        @Test
        @DisplayName("indices are remapped: the child's to its parent's, later ones down by one")
        void remap() {
            var moves = List.of(new int[]{5, 1}, new int[]{3, 2});
            assertThat(List.of(0, 1, 2, 3, 4, 5, 6).stream().map(i -> Rebirth.remap(i, moves)).toList())
                    .containsExactly(0, 1, 2, 2, 3, 1, 4);
        }

        @Test
        @DisplayName("a reborn topic is measured on the whole window, and passes the invariants")
        void rebornWholeWindow() {
            var state = state(0, topic("Old", EAST), topic("Back", NORTH).toBuilder()
                    .revivedAt(LocalDate.parse("2026-10-18")).build());
            var createdAt = new Instant[10];
            Arrays.fill(createdAt, 0, 5, Instant.parse("2026-10-01T00:00:00Z"));
            Arrays.fill(createdAt, 5, 10, Instant.parse("2026-10-15T00:00:00Z"));
            int[] labels = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0};

            assertThat(Lifecycle.retire(state, window(createdAt), labels, 10))
                    .containsExactly(Map.entry(0, 0.9), Map.entry(1, 0.1));

            var reborn = state(0, topic("A", EAST), dormant("B", RetiredReason.LOW_SHARE, NORTH));
            reborn.topic(reborn.born("B Again", "def")).setPrototypes(List.of(NORTH));
            reborn.inherit(2, 1);
            var names = Map.of(reborn.topic(0).getId(), "A", reborn.topic(1).getId(), "B");
            NightInvariants.check(reborn, names, List.of(reborn.topic(1).getId()), new int[]{0, 1}, 2, 10, 2);
        }

        private static NightState.Topic dormant(String name, RetiredReason reason, float[]... prototypes) {
            return topic(name, prototypes).toBuilder().retiredAt(NIGHT.minusDays(5)).retiredReason(reason)
                    .lowNights(3).build();
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

            var shares = Lifecycle.retire(state, window(300, Instant.parse("2026-10-15T00:00:00Z")), labels, 10);

            assertThat(shares.get(0)).isEqualTo(1.0 / 300);
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.LOW_SHARE);
            assertThat(state.topic(1).getLowNights()).isZero();
            assertThat(state.live()).containsExactly(1, 2);
        }

        @Test
        @DisplayName("over the cap the smallest count goes, ties to the earlier topic")
        void cap() {
            var state = state(0, topic("A"), topic("B"), topic("C"), topic("D"));
            int[] labels = {0, 0, 0, 1, 2, 3, 3, 3, 3};

            Lifecycle.retire(state, window(9, Instant.parse("2026-10-15T00:00:00Z")), labels, 2);

            assertThat(state.topic(1).getRetiredReason()).isEqualTo(RetiredReason.CAP);
            assertThat(state.topic(2).getRetiredReason()).isEqualTo(RetiredReason.CAP);
            assertThat(state.live()).containsExactly(0, 3);
        }

        @Test
        @DisplayName("a topic born after the first build is measured on the whole window, as any other")
        void newborn() {
            var state = state(0, topic("Old"), topic("New", LocalDate.parse("2026-10-18")));
            var createdAt = new Instant[10];
            Arrays.fill(createdAt, 0, 5, Instant.parse("2026-10-01T00:00:00Z"));
            Arrays.fill(createdAt, 5, 10, Instant.parse("2026-10-15T00:00:00Z"));
            int[] labels = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0};

            var shares = Lifecycle.retire(state, window(createdAt), labels, 10);

            assertThat(shares).containsExactly(Map.entry(0, 0.9), Map.entry(1, 0.1));
        }

        @Test
        @DisplayName("three quiet weeks make a topic inactive, two busy nights make it active again")
        void inactive() {
            var quiet = topic("Quiet").toBuilder().lo7(2).build();
            var back = topic("Back").toBuilder().inactiveSince(LocalDate.parse("2026-10-10")).hi7(1).lo7(3).build();
            var state = state(0, quiet, back);
            var labels = new int[100];
            Arrays.fill(labels, 1);

            Lifecycle.display(state, window(100, Instant.parse("2026-10-15T00:00:00Z")), labels);

            assertThat(state.topic(0).getInactiveSince()).isEqualTo(NIGHT);
            assertThat(state.topic(0).getSize()).isZero();
            assertThat(state.topic(1).getInactiveSince()).isNull();
            assertThat(state.topic(1).getLo7()).isZero();
            assertThat(state.topic(1).getSize()).isEqualTo(100);
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

            var result = NightlyUpdate.run(state, window(30, WINDOW_DAY), 10, NightClusterer.KMEANS, NO_LLM);

            assertThat(result.groupMap()).isEmpty();
            assertThat(result.candidates()).isEmpty();
            assertThat(result.labels()).containsOnly(0);
            assertThat(state.topic(0).getPrototypes()).containsExactly(EAST);
            assertThat(state.getOtherPrototypes()).containsExactly(SOUTH);
        }

        @Test
        @DisplayName("the night's one assignment is the final one: a known-Other prototype near a topic's is gated")
        void sameAsFinal() {
            var state = state(1, topic("East", EAST), topic("North", NORTH));
            state.setOtherPrototypes(List.of(new float[]{0.99f, 0.141f}, SOUTH));
            var window = window(three(10, 10, 10));

            var owners = NightlyUpdate.assign(state, window, state.getOtherPrototypes());
            var labels = NightlyUpdate.assignFinal(state, window);

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

            var result = NightlyUpdate.run(state, window(twoSmall()), 10, NightClusterer.KMEANS,
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
        @DisplayName("small groups the naming merges into one new topic are born when their window share reaches 2%")
        void merged() {
            var state = state(1, topic("East", EAST), topic("North", NORTH));

            var result = NightlyUpdate.run(state, window(twoSmall()), 10, NightClusterer.KMEANS,
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

            var result = NightlyUpdate.run(state, window(three(1980, 20, 0)), 10, NightClusterer.KMEANS,
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

            var result = NightlyUpdate.run(state, window(three(20, 40, 40)), 10, NightClusterer.KMEANS,
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
        @DisplayName("a group left in Other is kept in the memory once; a prototype that holds its traces is not kept twice")
        void memoryRefused() {
            var state = state(1, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));

            var result = NightlyUpdate.run(state, window(three(20, 40, 40)), 10, NightClusterer.KMEANS,
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
        @DisplayName("a topic born tonight on a dormant topic's traces is that topic again, with its id and name")
        void rebirth() {
            var dormant = topic("South", SOUTH).toBuilder().retiredAt(NIGHT.minusDays(5))
                    .retiredReason(RetiredReason.LOW_SHARE).lowNights(3).build();
            var state = state(1, topic("East", EAST), dormant);

            var result = NightlyUpdate.run(state, window(three(1000, 30, 0)), 10, NightClusterer.KMEANS,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"South Again\", \"definition\": \"S.\", \"clusters\": [0, 1]}"))));

            assertThat(result.revived()).containsExactly(1);
            assertThat(state.getTopics()).extracting(NightState.Topic::getName).containsExactly("East", "South");
            assertThat(state.live()).containsExactly(0, 1);
            assertThat(state.topic(1).getRevivedAt()).isEqualTo(NIGHT);
            assertThat(state.topic(1).getPrototypes()).hasSize(1);
            assertThat(result.forced()).extracting(ForcedBirth.Outcome::topic).containsOnly(1);
            assertThat(result.groupMap().values()).containsOnly(1);
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 1).count()).isEqualTo(30);
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

            var result = NightlyUpdate.run(state, window(vectors), 1, NightClusterer.KMEANS,
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
        @DisplayName("a topic's prototypes never move: a night without decisions leaves the list as it was")
        void frozen() {
            float[] tilted = {0.9f, 0.43589f};
            var state = state(1, topic("East", tilted));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 40; i++) {
                vectors.add(i < 30 ? EAST : new float[]{0.95f, 0.31225f});
            }

            var result = NightlyUpdate.run(state, window(vectors), 10, NightClusterer.KMEANS, NO_LLM);

            assertThat(result.labels()).containsOnly(0);
            assertThat(state.topic(0).getPrototypes()).singleElement().isSameAs(tilted);
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

        private final UUID a = UUID.randomUUID();
        private final UUID b = UUID.randomUUID();
        private final UUID other = TraceTopic.OTHER;

        @Test
        @DisplayName("churn counts topic-to-topic moves, churn_any every move, over traces labelled on both nights")
        void churn() {
            var t = new UUID[]{UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID()};
            var previous = Map.of(t[0], a, t[1], a, t[2], b, t[3], other);

            var result = TopicChurn.of(previous, t, new UUID[]{a, b, other, a, b});

            assertThat(result.churn()).isEqualTo(0.5);
            assertThat(result.churnAny()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("no trace on both nights is no churn; no trace in a topic on both is 0")
        void empty() {
            var t = new UUID[]{UUID.randomUUID()};

            assertThat(TopicChurn.of(Map.of(), t, new UUID[]{a})).isNull();
            assertThat(TopicChurn.of(Map.of(t[0], other), t, new UUID[]{a}))
                    .isEqualTo(new TopicChurn.Churn(0, 1));
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

            NightInvariants.check(state, names(state, 1), List.of(), new int[]{0, 1, -1}, 3, 10, 2);
        }

        @Test
        @DisplayName("11 live topics fail the run")
        void tooMany() {
            var topics = new NightState.Topic[11];
            for (int t = 0; t < topics.length; t++) {
                topics[t] = topic("T" + t, EAST);
            }
            var state = state(0, topics);

            assertInvariant(() -> NightInvariants.check(state, names(state, 11), List.of(), new int[0], 0, 10, 2),
                    "'11' live topics");
        }

        @Test
        @DisplayName("a label on a retired topic fails the run")
        void labelOnRetired() {
            var state = state(0, topic("A", EAST), topic("B", NORTH));
            state.retire(1, RetiredReason.CAP);

            assertInvariant(() -> NightInvariants.check(state, names(state, 2), List.of(), new int[]{0, 1}, 2, 10, 2),
                    "not a live topic");
        }

        @Test
        @DisplayName("a retired id that is live again, a rename, a catch-all newborn or a long prototype fail the run")
        void others() {
            var revived = state(0, topic("A", EAST));
            assertInvariant(() -> NightInvariants.check(revived, names(revived, 1), List.of(revived.topic(0).getId()),
                    new int[0], 0, 10, 2), "is live");

            var renamed = state(0, topic("A", EAST));
            assertInvariant(() -> NightInvariants.check(renamed, Map.of(renamed.topic(0).getId(), "Old"), List.of(),
                    new int[0], 0, 10, 2), "renamed");

            var catchAll = state(0, topic("A", EAST));
            catchAll.born("Other Things", "x");
            assertInvariant(() -> NightInvariants.check(catchAll, names(catchAll, 1), List.of(), new int[0], 0, 10,
                    2), "catch-all");

            var long_ = state(0, topic("A", new float[]{1, 1}));
            assertInvariant(() -> NightInvariants.check(long_, names(long_, 1), List.of(), new int[0], 0, 10, 2),
                    "norm");
        }

        private static Map<UUID, String> names(NightState state, int read) {
            var out = new LinkedHashMap<UUID, String>();
            for (int t = 0; t < read; t++) {
                out.put(state.topic(t).getId(), state.topic(t).getName());
            }
            return out;
        }

        private static void assertInvariant(Runnable check, String message) {
            assertThatThrownBy(check::run).isInstanceOfSatisfying(TopicsRunException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo(Code.INVARIANT);
                assertThat(exception.getMessage()).contains(message);
            });
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
                .firstBuild(FIRST_BUILD)
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
        return new GroupSummary(group, size, 0.1, recentShare, tightness, List.of("a", "b"), 2);
    }

    /** A group with this share of the window. */
    private static GroupSummary sized(int group, int size, double tightness, double share) {
        return new GroupSummary(group, size, share, share, tightness, List.of("a", "b"), 2);
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
