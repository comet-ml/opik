package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ImplementedBy;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The topic algorithm: how a project's window of vectors becomes its topic list, and the rule that labels a trace by a
 * saved list. {@link TopicsRebuildService} does everything around a build: it reads the window, makes the LLM calls the
 * algorithm asks for, checks the prototypes, saves and counts. {@link TopicWindowLabelService} labels the window's
 * other traces with {@link #labeller}. To try another algorithm, implement this interface and bind it in place of
 * {@link KMeansTopicAlgorithm}.
 *
 * <p>The limit of this seam: an algorithm leaves its list in the saved format the service owns. That is the topics
 * (name, definition; the service sets their lifecycle columns), the prototype vectors of each topic, and known-Other
 * prototypes. A trace is labelled by the nearest saved prototype. An algorithm that cannot express its result as
 * prototypes does not fit this seam.
 *
 * <p>The labels a build returns should be the ones its {@link #labeller} gives with the prototypes it returns:
 * otherwise a trace the build read and a trace the labelling pass labels get their topic by two different rules.
 */
@ImplementedBy(KMeansTopicAlgorithm.class)
public interface TopicAlgorithm {

    /** The label of a trace in no topic. */
    int OTHER = -1;

    /**
     * Builds the first list of a project that has none.
     *
     * @param maxTopics the most topics the list may hold
     */
    FirstBuild firstBuild(Window window, int maxTopics, Context context);

    /**
     * The rule that labels vectors by a project's saved list. Empty when the list has no topic prototype: then it
     * labels nothing.
     *
     * <p>It reads the prototype rows only, not the topics: it relies on the storage rule that a retired topic keeps no
     * prototype rows, so every row with a {@code topic_id} belongs to a live topic.
     *
     * @param prototypes the project's saved prototype rows in {@code position} order: rows with a {@code topic_id}
     *                   are the topics' prototypes, rows without are known-Other prototypes
     */
    Optional<Labeller> labeller(List<TopicPrototype> prototypes);

    /** Labels vectors by one saved list. Local and fast: no network call. */
    @FunctionalInterface
    interface Labeller {

        /**
         * The topic of each vector: a topic's id, or {@link TraceTopic#OTHER}.
         *
         * @param vectors {@value TopicsRebuildService#DIMENSIONS} values per vector, one after another
         */
        UUID[] label(float[] vectors);
    }

    /**
     * The kinds of LLM call a run makes. The service picks the call's deadline and its metric label from the kind.
     */
    @Getter
    @RequiredArgsConstructor
    enum Call {
        /** Names the topics over the groups. Deadline {@code topics.build.qwenDeadline}. */
        AUTHOR("author");

        private final String value;
    }

    /** What a run gives its algorithm: the LLM, the phase timer and the run's stats. */
    interface Context {

        /**
         * One LLM call. {@code read} turns the answer into what the algorithm needs; when it cannot, it throws a
         * {@code qwen_bad_answer} {@link TopicsRunException}, and the call counts as {@code bad_answer}. A failed call
         * throws and fails the run.
         *
         * @param call the kind of call: picks its deadline and metric label
         * @param tag  names the call in its request id ({@code <runId>:<tag>}) and the logs
         */
        <T> T generate(Call call, String tag, String prompt, JsonNode schema, int maxTokens,
                Function<JsonNode, T> read);

        /** Runs {@code action} as a phase of the run: its seconds go to the run's stats and to the phase metric. */
        <T> T timed(String phase, Supplier<T> action);

        /** The run's stats, saved on its {@code topic_runs} row; an algorithm adds its own. */
        ObjectNode stats();
    }

    /**
     * The window a build reads: per trace, in time order, its intent text and its vector of
     * {@value TopicsRebuildService#DIMENSIONS} values ({@code vectors} holds them one after another).
     */
    record Window(@NonNull String[] texts, @NonNull float[] vectors) {

        public int size() {
            return texts.length;
        }
    }

    /** A new topic, before it has an id. */
    @Builder(toBuilder = true)
    record NewTopic(@NonNull String name, @NonNull String definition) {
    }

    /**
     * A first list. {@code labels[i]} is the index in {@code topics} of window trace i's topic, or {@link #OTHER}. The
     * prototypes are saved in this order: {@code topicPrototypes}, each owned by the topic at the same index of
     * {@code owners}, then {@code otherPrototypes}.
     */
    @Builder(toBuilder = true)
    record FirstBuild(@NonNull List<NewTopic> topics, @NonNull int[] labels, @NonNull List<float[]> topicPrototypes,
            @NonNull List<Integer> owners, @NonNull List<float[]> otherPrototypes) {
    }
}
