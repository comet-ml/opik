package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ImplementedBy;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The topic algorithm: how a project's window of vectors becomes its topic list on the first night, and how that list
 * changes on every night after. {@link TopicsRebuildService} does everything around it: it reads the window and the
 * saved list, makes the LLM calls the algorithm asks for, checks the invariants, computes the churn, saves and counts.
 * To try another algorithm, implement this interface and bind it in place of {@link KMeansTopicAlgorithm}.
 *
 * <p>What an algorithm leaves is the saved list: topics, each with prototype vectors, and known-Other prototypes. Topic
 * on arrival ({@link TopicsArrivalService}) labels a new trace by its nearest prototype ({@link PrototypeAssigner}:
 * cosine 0.75, Other gate 0.95), so the labels an algorithm returns should be the ones that rule gives with the
 * prototypes it leaves; otherwise arrival and the night disagree. An algorithm without prototypes needs another
 * arrival.
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
