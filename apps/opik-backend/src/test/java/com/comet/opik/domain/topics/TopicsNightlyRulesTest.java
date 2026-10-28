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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The nightly update's rules on small synthetic inputs: the paths the three-night fixture does not reach. */
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
    private static final NightLlm NO_LLM = (tag, prompt, schema, maxTokens) -> {
        throw new AssertionError("unexpected Qwen call '%s'".formatted(tag));
    };

    @Nested
    @DisplayName("revise apply")
    class Revise {

        @Test
        @DisplayName("assign takes candidates once and only to live topics")
        void assign() {
            var state = state(0, topic("A"), topic("B"));
            var map = ReviseApplier.apply(state, json("""
                    {"assign": [{"cluster": 0, "topic": 2}, {"cluster": 0, "topic": 1}, {"cluster": 9, "topic": 1},
                                {"cluster": 1, "topic": 7}, {"cluster": "1", "topic": 1}, {"cluster": 2, "topic": 1}]}
                    """), shown(state), Set.of(0, 1, 2), false, 10);

            assertThat(map).containsExactly(Map.entry(0, 1), Map.entry(2, 0));
        }

        @Test
        @DisplayName("on a review night merge moves groups and prototypes, retire sends groups to Other, redefine keeps the name")
        void review() {
            var state = state(0, topic("A", EAST), topic("B", NORTH), topic("C", SOUTH));
            var map = ReviseApplier.apply(state, json("""
                    {"assign": [{"cluster": 0, "topic": 2}, {"cluster": 1, "topic": 3}],
                     "merge": [[1, 2], [1, 1], [1]],
                     "retire": [3, 9],
                     "redefine": [{"id": 1, "definition": "  Both A and B. "}, {"id": 2, "definition": "gone"}]}
                    """), List.of(prompt(1, 0, 0.1), prompt(2, 1, 0.1), small(3, 2)), Set.of(0, 1), true, 10);

            assertThat(map).containsExactly(Map.entry(0, 0), Map.entry(1, ReviseApplier.OTHER));
            assertThat(state.topic(0).getPrototypes()).containsExactly(EAST, NORTH);
            assertThat(state.topic(0).getName()).isEqualTo("A");
            assertThat(state.topic(0).getDefinition()).isEqualTo("Both A and B.");
            assertThat(state.topic(1).getRetiredReason()).isEqualTo(RetiredReason.MERGED);
            assertThat(state.topic(2).getRetiredReason()).isEqualTo(RetiredReason.REVIEW);
            assertThat(state.live()).containsExactly(0);
        }

        @Test
        @DisplayName("a review does not retire a topic with a 2% share, overall or in the last 7 days")
        void reviewKeepsTopicsWithTraffic() {
            var state = state(0, topic("A"), topic("B"), topic("C"));
            var shown = List.of(prompt(1, 0, 0.4), share(2, 1, 0.01, 0.03), small(3, 2));
            ReviseApplier.apply(state, json("""
                    {"retire": [1, 2, 3]}
                    """), shown, Set.of(), true, 10);

            assertThat(state.live()).containsExactly(0, 1);
            assertThat(state.topic(2).getRetiredReason()).isEqualTo(RetiredReason.REVIEW);
        }

        @Test
        @DisplayName("merge, retire and redefine are ignored on a normal night")
        void normalNight() {
            var state = state(0, topic("A"), topic("B"));
            ReviseApplier.apply(state, json("""
                    {"merge": [[1, 2]], "retire": [1], "redefine": [{"id": 1, "definition": "x"}]}
                    """), shown(state), Set.of(), false, 10);

            assertThat(state.live()).containsExactly(0, 1);
            assertThat(state.topic(0).getDefinition()).isEqualTo("A def");
        }

        @Test
        @DisplayName("new skips catch-all names and a full list, takes only unmapped candidates; other fills the rest")
        void newAndOther() {
            var state = state(0, topic("A"));
            var map = ReviseApplier.apply(state, json("""
                    {"assign": [{"cluster": 0, "topic": 1}],
                     "new": [{"name": "Other Requests", "definition": "x", "clusters": [1]},
                             {"name": " Weather ", "definition": "Forecasts.", "clusters": [0, 1, 7]},
                             {"name": "Late", "definition": "y", "clusters": [2]}],
                     "other": [0, 2, 3, 9]}
                    """), shown(state), Set.of(0, 1, 2, 3), false, 2);

            assertThat(map).containsExactly(Map.entry(0, 0), Map.entry(1, 1), Map.entry(2, ReviseApplier.OTHER),
                    Map.entry(3, ReviseApplier.OTHER));
            assertThat(state.topic(1).getName()).isEqualTo("Weather");
            assertThat(state.topic(1).getBorn()).isEqualTo(NIGHT);
            assertThat(state.liveCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("an answer that is not an object is qwen_bad_answer")
        void badAnswer() {
            var state = state(0, topic("A"));
            assertThatThrownBy(() -> ReviseApplier.apply(state, json("[]"), shown(state), Set.of(), false, 10))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_BAD_ANSWER));
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        private final NightState state = state(0, topic("A"));
        private final List<GroupSummary> ordered = List.of(group(3, 50, 0.9), group(1, 30, 0.9), group(2, 25, 0.9));
        private final Map<Integer, Integer> map = new LinkedHashMap<>(Map.of(1, 0, 3, 0, 2, ReviseApplier.OTHER));

        @Test
        @DisplayName("pairs go largest first; a missing or out-of-range pair is not verified; same follows Python truth")
        void answers() {
            var result = Verifier.verify(ordered, topics(state), map, (tag, prompt, schema, maxTokens) -> {
                assertThat(tag).isEqualTo("verify");
                assertThat(maxTokens).isEqualTo(60 + 25 * 2);
                assertThat(prompt).contains("PAIR 0", "PAIR 1").doesNotContain("PAIR 2");
                return json("""
                        {"answers": [{"pair": 0, "same": ""}, {"pair": 1}, {"pair": 2, "same": true}]}
                        """);
            });

            assertThat(result.pairs()).extracting(pair -> pair[0]).containsExactly(3, 1);
            assertThat(result.verdicts()).containsOnly(Map.entry(3, false), Map.entry(1, true));
            assertThat(result.verified(3)).isFalse();
            assertThat(result.verified(1)).isTrue();
        }

        @Test
        @DisplayName("an answer without the pair leaves it not verified")
        void missing() {
            var result = Verifier.verify(ordered, topics(state), map,
                    (tag, prompt, schema, maxTokens) -> json("{\"answers\": [{\"pair\": 1, \"same\": 1}]}"));

            assertThat(result.verified(3)).isFalse();
            assertThat(result.verified(1)).isTrue();
        }

        @Test
        @DisplayName("a failed call fails the run: revise's assigns are not turned into forced births")
        void failed() {
            assertThatThrownBy(() -> Verifier.verify(ordered, topics(state), map, (tag, prompt, schema, maxTokens) -> {
                throw new TopicsRunException(Code.QWEN_TIMEOUT, "timeout");
            })).isInstanceOfSatisfying(TopicsRunException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_TIMEOUT));
        }

        @Test
        @DisplayName("an unreadable answer verifies nothing and the night goes on")
        void unreadable() {
            var result = Verifier.verify(ordered, topics(state), map, (tag, prompt, schema, maxTokens) -> {
                throw new IllegalStateException("unreadable");
            });

            assertThat(result.called()).isTrue();
            assertThat(result.failed()).isTrue();
            assertThat(result.verdicts()).isEmpty();
        }

        @Test
        @DisplayName("no mapped pair means no call")
        void noPairs() {
            var result = Verifier.verify(ordered, topics(state), Map.of(), NO_LLM);

            assertThat(result.called()).isFalse();
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
    @DisplayName("forced birth")
    class Forced {

        @Test
        @DisplayName("a rejected pair is unmapped; a big one gets a topic, a loose one known Other, a small one nothing")
        void outcomes() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>(Map.of(0, 0, 1, 0, 2, 0));
            var verified = verified(Map.of(0, false, 1, false, 2, false));
            var outcomes = force(state, List.of(group(0, 60, 0.95), group(1, 50, 0.79), group(2, 10, 0.95)),
                    Set.of(0, 1), topics(state), verified, map, 10,
                    named(Map.of("naming",
                            newTopics("{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [0]}"))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(1, ForcedBirth.Result.OTHER_LOOSE, null),
                    new ForcedBirth.Outcome(0, ForcedBirth.Result.BORN, 1));
            assertThat(map).containsOnly(Map.entry(0, 1), Map.entry(1, ReviseApplier.OTHER));
            assertThat(state.topic(1).getName()).isEqualTo("Weather");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "a catch-all name|{\"name\": \"General Help\", \"definition\": \"x\", \"clusters\": [0]}",
                "an empty name|{\"name\": \"\", \"definition\": \"x\", \"clusters\": [0]}",
                "a blank name|{\"name\": \" \\u00A0 \", \"definition\": \"x\", \"clusters\": [0]}",
                "an unknown cluster|{\"name\": \"Weather\", \"definition\": \"x\", \"clusters\": [7]}",
                "no new topic|"})
        @DisplayName("an answer that gives a forced group no usable topic sends it to known Other")
        void noName(String label, String entry) {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95)), Set.of(0), topics(state), verified(Map.of()),
                    map, 10, named(Map.of("naming", newTopics(entry == null ? "" : entry))));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.OTHER_NAME, null));
            assertThat(map).containsOnly(Map.entry(0, ReviseApplier.OTHER));
            assertThat(state.liveCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("an answer that is not an object leaves every forced group in known Other")
        void unreadable() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95)), Set.of(0), topics(state), verified(Map.of()),
                    map, 10, named(Map.of("naming", "\"Sorry\"")));

            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.OTHER_NAME, null));
        }

        @Test
        @DisplayName("same-kind groups form one topic, a repeated group counts once, equal new names merge")
        void grouped() {
            var state = state(0, topic("A"));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state, List.of(group(0, 60, 0.95), group(1, 50, 0.95), group(2, 40, 0.95)),
                    Set.of(0, 1, 2), topics(state), verified(Map.of()), map, 10,
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
            var outcomes = force(state, List.of(group(0, 60, 0.95)), Set.of(0), topics(state), verified(Map.of()),
                    map, 10, named(Map.of("naming",
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
                    Set.of(0, 1, 2), topics(state), verified(Map.of()), map, 10, llm);

            assertThat(tags).containsExactly("naming", "verify_naming", "naming_new");
            assertThat(outcomes).containsExactly(new ForcedBirth.Outcome(0, ForcedBirth.Result.ASSIGNED, 1),
                    new ForcedBirth.Outcome(1, ForcedBirth.Result.BORN, 2),
                    new ForcedBirth.Outcome(2, ForcedBirth.Result.OTHER_NAME, null));
            assertThat(map).containsOnly(Map.entry(0, 1), Map.entry(1, 2), Map.entry(2, ReviseApplier.OTHER));
        }

        @Test
        @DisplayName("on a full list the oldest-born lowest 7-day share is replaced if below the new topic, never one born tonight")
        void fullList() {
            var state = state(0, topic("A"), topic("B"));
            var topics = new LinkedHashMap<Integer, TopicPrompts.PromptTopic>();
            topics.put(0, prompt(1, 0, 0.30));
            topics.put(1, prompt(2, 1, 0.10));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state,
                    List.of(group(0, 60, 0.95, 0.20), group(1, 50, 0.95, 0.40), group(2, 40, 0.95, 0.05)),
                    Set.of(0, 1, 2), topics, verified(Map.of()), map, 2,
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
        @DisplayName("a new topic's 7-day share is summed as Python does: 0.1 + 0.2 + 0.3 is above 0.6")
        void pythonSum() {
            var state = state(0, topic("A"));
            var topics = new LinkedHashMap<Integer, TopicPrompts.PromptTopic>();
            topics.put(0, prompt(1, 0, 0.6));
            var map = new LinkedHashMap<Integer, Integer>();
            var outcomes = force(state,
                    List.of(group(0, 60, 0.95, 0.1), group(1, 50, 0.95, 0.2), group(2, 40, 0.95, 0.3)),
                    Set.of(0, 1, 2), topics, verified(Map.of()), map, 1,
                    named(Map.of("naming", newTopics(
                            "{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [0, 1, 2]}"))));

            assertThat(outcomes).extracting(ForcedBirth.Outcome::result).containsOnly(ForcedBirth.Result.BORN);
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
        }

        @Test
        @DisplayName("the prompt shows the live topics and the forced groups largest first")
        void namingPrompt() {
            var state = state(0, topic("A"));
            var prompts = new ArrayList<String>();
            force(state, List.of(group(0, 60, 0.95), group(1, 70, 0.95)), Set.of(0, 1), topics(state),
                    verified(Map.of()), new LinkedHashMap<>(), 10, (tag, prompt, schema, maxTokens) -> {
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
    @DisplayName("revival")
    class Revivals {

        @Test
        @DisplayName("a dormant topic whose traces reach the big share comes back with its id, name and born")
        void comesBack() {
            var state = state(0, topic("East", EAST), dormant("North", RetiredReason.LOW_SHARE, NORTH));
            var id = state.topic(1).getId();

            var revived = Revival.apply(state, window(directions(90, 10)), 10);

            assertThat(revived).containsExactly(1);
            var topic = state.topic(1);
            assertThat(topic.isLive()).isTrue();
            assertThat(topic.getId()).isEqualTo(id);
            assertThat(topic.getName()).isEqualTo("North");
            assertThat(topic.getBorn()).isEqualTo(FIRST_BUILD);
            assertThat(topic.getRevivedAt()).isEqualTo(NIGHT);
            assertThat(topic.getRetiredReason()).isNull();
            assertThat(topic.getLowNights()).isZero();
            assertThat(topic.getInactiveSince()).isNull();
        }

        @Test
        @DisplayName("a small comeback, a full list, a topic Qwen retired or one retired tonight stays retired")
        void staysRetired() {
            var small = state(0, topic("East", EAST), dormant("North", RetiredReason.CAP, NORTH));
            assertThat(Revival.apply(small, window(directions(99, 1)), 10)).isEmpty();
            assertThat(small.topic(1).isLive()).isFalse();

            var full = state(0, topic("East", EAST), dormant("North", RetiredReason.REPLACED, NORTH));
            assertThat(Revival.apply(full, window(directions(50, 50)), 1)).isEmpty();

            for (var reason : List.of(RetiredReason.MERGED, RetiredReason.REVIEW)) {
                var judged = state(0, topic("East", EAST), dormant("North", reason, NORTH));
                assertThat(judged.dormant()).isEmpty();
                assertThat(Revival.apply(judged, window(directions(50, 50)), 10)).isEmpty();
            }

            var tonight = state(0, topic("East", EAST), topic("North", NORTH));
            tonight.retire(1, RetiredReason.LOW_SHARE);
            assertThat(tonight.dormant()).isEmpty();
        }

        @Test
        @DisplayName("traces nearer a live topic or known Other do not count for the dormant one")
        void nearestOwnerOnly() {
            var close = new float[]{0.8f, 0.6f};
            var state = state(0, topic("East", EAST), dormant("Near", RetiredReason.LOW_SHARE, close));
            state.setOtherPrototypes(List.of(NORTH));
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 100; i++) {
                vectors.add(i < 50 ? EAST : NORTH);
            }

            assertThat(Revival.apply(state, window(vectors), 10)).isEmpty();
        }

        @Test
        @DisplayName("a revived topic is measured on the whole window, and passes the invariants")
        void revivedWholeWindow() {
            var state = state(0, topic("Old", EAST), topic("Back", NORTH).toBuilder()
                    .revivedAt(LocalDate.parse("2026-10-18")).build());
            var createdAt = new Instant[10];
            Arrays.fill(createdAt, 0, 5, Instant.parse("2026-10-01T00:00:00Z"));
            Arrays.fill(createdAt, 5, 10, Instant.parse("2026-10-15T00:00:00Z"));
            int[] labels = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0};

            assertThat(Lifecycle.retire(state, window(createdAt), labels, 10))
                    .containsExactly(Map.entry(0, 0.9), Map.entry(1, 0.1));

            var revived = state(0, topic("A", EAST), dormant("B", RetiredReason.LOW_SHARE, NORTH));
            revived.revive(1);
            var names = Map.of(revived.topic(0).getId(), "A", revived.topic(1).getId(), "B");
            NightInvariants.check(revived, names, List.of(revived.topic(1).getId()), new int[]{0, 1}, 2, 10, 2);
        }

        private static NightState.Topic dormant(String name, RetiredReason reason, float[]... prototypes) {
            return topic(name, prototypes).toBuilder().retiredAt(NIGHT.minusDays(5)).retiredReason(reason)
                    .lowNights(3).build();
        }

        private static List<float[]> directions(int east, int north) {
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < east + north; i++) {
                vectors.add(i < east ? EAST : NORTH);
            }
            return vectors;
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
        @DisplayName("a topic born after the first build is measured from born - 7 days")
        void newborn() {
            var state = state(0, topic("Old"), topic("New", LocalDate.parse("2026-10-18")));
            var createdAt = new Instant[10];
            Arrays.fill(createdAt, 0, 5, Instant.parse("2026-10-01T00:00:00Z"));
            Arrays.fill(createdAt, 5, 10, Instant.parse("2026-10-15T00:00:00Z"));
            int[] labels = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0};

            var shares = Lifecycle.retire(state, window(createdAt), labels, 10);

            assertThat(shares).containsExactly(Map.entry(0, 0.9), Map.entry(1, 0.2));
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
    @DisplayName("recentre")
    class Recentring {

        @ParameterizedTest(name = "{0} members, {1} unique texts: k = {2}")
        @CsvSource({"10, 10, 1", "49, 49, 1", "50, 50, 2", "50, 1, 1", "450, 450, 3", "2000, 2000, 10",
                "2000, 4, 4"})
        @DisplayName("k follows the member count with a floor of 2 from 50 members")
        void k(int members, int unique, int expected) {
            assertThat(Recentre.k(members, unique)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a warm start when the old prototype count equals k, k-means++ with seed 0 otherwise")
        void warmStart() {
            var window = window(10, Instant.parse("2026-10-15T00:00:00Z"));
            var requests = new ArrayList<NightClusterer.Request>();
            NightClusterer clusterer = request -> {
                requests.add(request);
                return NightClusterer.KMEANS.fit(request);
            };
            int[] members = {0, 1, 2};

            Recentre.run(window, members, List.of(EAST), UUID.randomUUID(), clusterer);
            Recentre.run(window, members, List.of(EAST, NORTH), null, clusterer);

            assertThat(requests.get(0).init()).containsExactly(EAST);
            assertThat(requests.get(0).kind()).isEqualTo(NightClusterer.Kind.RECENTRE_TOPIC);
            assertThat(requests.get(1).init()).isNull();
            assertThat(requests.get(1).kind()).isEqualTo(NightClusterer.Kind.RECENTRE_OTHER);
            assertThat(requests).extracting(NightClusterer.Request::seed).containsOnly(0L);
        }
    }

    @Nested
    @DisplayName("the whole night")
    class Night {

        @Test
        @DisplayName("without a big candidate and outside a review night there is no Qwen call")
        void quiet() {
            var state = state(1, topic("East", EAST));
            var result = NightlyUpdate.run(state, window(30, WINDOW_DAY), 10, NightClusterer.KMEANS, NO_LLM);

            assertThat(result.review()).isFalse();
            assertThat(result.groupMap()).isEmpty();
            assertThat(result.labels()).containsOnly(0);
        }

        @Test
        @DisplayName("a review night drops the known-Other memory and calls revise with the review prompt")
        void reviewNight() {
            var state = state(7, topic("East", EAST));
            state.setOtherPrototypes(List.of(SOUTH));
            var prompts = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(30, WINDOW_DAY), 10, NightClusterer.KMEANS,
                    (tag, prompt, schema, maxTokens) -> {
                        prompts.add(tag);
                        var east = TopicPrompts.PromptTopic.builder().id(1).topic(0).name("East")
                                .definition("East def").share(1.0).recentShare(1.0)
                                .examples(List.of("t0", "t1", "t10", "t11")).build();
                        assertThat(prompt).isEqualTo(TopicPrompts.revise(List.of(east), List.of(), true, 10));
                        return json("{}");
                    });

            assertThat(result.reviewNight()).isTrue();
            assertThat(prompts).containsExactly("revise");
            assertThat(state.getOtherPrototypes()).isEmpty();
        }

        @Test
        @DisplayName("a big candidate on a full list forces a review and replaces the weakest topic")
        void fullList() {
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 100; i++) {
                vectors.add(i < 20 ? EAST : i < 60 ? NORTH : SOUTH);
            }
            var state = state(1, topic("East", EAST));
            var tags = new ArrayList<String>();

            var result = NightlyUpdate.run(state, window(vectors), 1, NightClusterer.KMEANS,
                    (tag, prompt, schema, maxTokens) -> {
                        tags.add(tag);
                        return tag.equals("revise")
                                ? json("{}")
                                : json(newTopics("{\"name\": \"Weather\", \"definition\": \"F.\", \"clusters\": [0]}, "
                                        + "{\"name\": \"Rain\", \"definition\": \"F.\", \"clusters\": [1]}"));
                    });

            assertThat(result.review()).isTrue();
            assertThat(result.big()).containsExactly(0, 1);
            assertThat(tags).hasSize(2).first().isEqualTo("revise");
            assertThat(tags.get(1)).isEqualTo("naming");
            assertThat(result.forced()).extracting(ForcedBirth.Outcome::result)
                    .containsExactly(ForcedBirth.Result.BORN, ForcedBirth.Result.OTHER_FULL);
            assertThat(state.topic(0).getRetiredReason()).isEqualTo(RetiredReason.REPLACED);
            assertThat(state.live()).containsExactly(1);
            assertThat(state.getOtherPrototypes()).hasSize(1);
            assertThat(Arrays.stream(result.labels()).filter(label -> label == 1).count()).isEqualTo(40);
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
        @DisplayName("revise shows (none) without candidates and the list size in both prompts")
        void revise() {
            var topics = List.of(prompt(1, 0, 0.25));

            assertThat(TopicPrompts.revise(topics, List.of(), false, 10)).contains("  (none)")
                    .doesNotContain("{max_topics}", "{topics}", "{candidates}");
            assertThat(TopicPrompts.revise(topics, List.of(group(0, 40, 0.9)), true, 10))
                    .doesNotContain("{max_topics}", "{topics}", "{candidates}");
        }

        @Test
        @DisplayName("a placeholder inside trace text stays as it is: the groups are shown once")
        void placeholderInText() {
            var base = prompt(1, 0, 0.25);
            var topic = new TopicPrompts.PromptTopic(1, 0, base.name(), "Asks about {candidates} and {clusters}",
                    base.share(), base.recentShare(), base.examples());
            var groups = List.of(group(0, 40, 0.9));

            var revise = TopicPrompts.revise(List.of(topic), groups, false, 10);
            var naming = TopicPrompts.namingGroup(List.of(topic), groups, true);

            assertThat(revise).contains("Asks about {candidates} and {clusters}");
            assertThat(revise.split("cluster 0:", -1)).hasSize(2);
            assertThat(naming).contains("Asks about {candidates} and {clusters}");
            assertThat(naming.split("cluster 0:", -1)).hasSize(2);
        }

        @Test
        @DisplayName("verify shows the larger of the two shares of the new cluster")
        void verify() {
            var prompt = TopicPrompts.verify(List.of(new TopicPrompts.VerifyPair(prompt(1, 0, 0.25),
                    group(0, 40, 0.9, 0.031))));

            assertThat(prompt).contains("NEW CLUSTER (share 10.0%)");
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

    /** A prompt topic under the 2% bar, overall and in the last 7 days: a review may retire it. */
    private static TopicPrompts.PromptTopic small(int id, int topic) {
        return share(id, topic, 0.01, 0.01);
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

            assertThatThrownBy(
                    () -> force(state, List.of(group(0, 60, 0.95, 0.9)), Set.of(0), topics(state),
                            verified(Map.of()), map, 10, failing))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_ERROR));
        }
    }

    private static Verifier.Verified verified(Map<Integer, Boolean> verdicts) {
        return Verifier.Verified.builder().pairs(List.of()).verdicts(verdicts).called(true).failed(false).build();
    }

    private static List<ForcedBirth.Outcome> force(NightState state, List<GroupSummary> ordered, Set<Integer> big,
            Map<Integer, TopicPrompts.PromptTopic> topics, Verifier.Verified verified, Map<Integer, Integer> map,
            int maxTopics, NightLlm llm) {
        return ForcedBirth.apply(state, ordered, big, topics, verified, map, () -> List.copyOf(topics.values()),
                maxTopics, llm);
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
