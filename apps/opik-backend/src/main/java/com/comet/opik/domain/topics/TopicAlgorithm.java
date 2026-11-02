package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ImplementedBy;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The topic algorithm: how a project's window of vectors becomes its topic list on the first night, how that list
 * changes on every night after, and how a new trace gets a topic between nights. {@link TopicsRebuildService} does
 * everything around the nights: it reads the window and the saved list, makes the LLM calls the algorithm asks for,
 * checks the invariants, computes the churn, saves and counts. {@link TopicsArrivalService} does everything around
 * arrival: it reads the saved list, bounds the time, writes the rows and counts. To try another algorithm, implement
 * this interface and bind it in place of {@link KMeansTopicAlgorithm}.
 *
 * <p>What an algorithm leaves is the saved list: topics, their prototype vectors, and known-Other prototypes. The
 * labels a night returns should be the ones its {@link #arrival} rule gives with the list it leaves; otherwise a trace's
 * topic on arrival and its topic after the night disagree.
 */
@ImplementedBy(KMeansTopicAlgorithm.class)
public interface TopicAlgorithm {

    /**
     * Builds the first list of a project that has none.
     *
     * @param maxTopics the most topics the list may hold
     */
    FirstBuild firstBuild(@NonNull NightWindow window, int maxTopics, @NonNull Context context);

    /**
     * Updates the saved list for tonight's window: changes {@code state} in place (topics born, revived and retired,
     * prototypes, lifecycle columns, sizes) and labels every window trace.
     *
     * @param maxTopics the most live topics the list may hold
     */
    Update update(@NonNull NightState state, @NonNull NightWindow window, int maxTopics, @NonNull Context context);

    /**
     * The rule topic on arrival labels new traces with, from the list the last night saved. Empty when that list labels
     * nothing yet: arrival then writes no row, and the traces wait for the next night. Called once per project per
     * vectors tick, on the tick's thread.
     *
     * @param live the project's live topics
     * @param prototypes the project's saved prototypes by position: those of every topic it kept, and known Other
     */
    Optional<Arrival> arrival(@NonNull List<Topic> live, @NonNull List<TopicPrototype> prototypes);

    /**
     * A project's arrival rule for one tick. It runs on the vectors tick's thread, inside the tick's budget, so it must
     * be fast and local: no network call.
     */
    interface Arrival {

        /** The vector length it labels; arrival skips vectors of another length. */
        int dimensions();

        /**
         * The topic of each vector: a live topic's id, or {@link TraceTopic#OTHER}.
         *
         * @param vectors rows of {@link #dimensions()} values each
         */
        UUID[] label(@NonNull float[] vectors);
    }

    /** What a run gives its algorithm: the LLM, the phase timer and the run's stats. */
    interface Context {

        /**
         * One LLM call. {@code read} turns the answer into what the algorithm needs; when it cannot, it throws a
         * {@code qwen_bad_answer} {@link TopicsRunException}, and the call counts as {@code bad_answer}. A failed call
         * throws and fails the run.
         *
         * @param tag names the call in its request id, metrics and logs, and picks its deadline
         */
        <T> T generate(@NonNull String tag, @NonNull String prompt, @NonNull JsonNode schema, int maxTokens,
                @NonNull Function<JsonNode, T> read);

        /** Runs {@code action} as a phase of the run: its seconds go to the run's stats and to the phase metric. */
        <T> T timed(@NonNull String phase, @NonNull Supplier<T> action);

        /** The run's stats, saved on its {@code topic_runs} row; an algorithm adds its own. */
        ObjectNode stats();
    }

    /** A new topic, before it has an id. */
    record NewTopic(@NonNull String name, @NonNull String definition) {
    }

    /**
     * A first list. {@code labels[i]} is the index in {@code topics} of window trace i's topic, or
     * {@link PrototypeAssigner#OTHER}. The prototypes are saved in this order: {@code topicPrototypes}, each owned by the
     * topic at the same index of {@code owners}, then {@code otherPrototypes}.
     */
    @Builder
    record FirstBuild(@NonNull List<NewTopic> topics, @NonNull int[] labels, @NonNull List<float[]> topicPrototypes,
            @NonNull List<Integer> owners, @NonNull List<float[]> otherPrototypes) {
    }

    /** {@code labels[i]} is the index in the state of window trace i's topic, or {@link PrototypeAssigner#OTHER}. */
    record Update(@NonNull int[] labels) {
    }
}
