package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Function;
import java.util.function.Supplier;

/** What a run gives its {@link TopicAlgorithm}: the LLM, the phase timer and the run's stats. */
public interface TopicRunContext {

    /**
     * One LLM call. {@code read} turns the answer into what the algorithm needs; when it cannot, it throws an
     * {@code llm_bad_answer} {@link TopicsRunException}, and the call counts as {@code bad_answer}. A {@code read}
     * that returns null instead counts the call as {@code bad_answer} too, and the run goes on: {@code generate} returns
     * null. A failed call throws and fails the run.
     *
     * @param tag names the call in its request id ({@code <runId>:<tag>}) and the logs
     */
    <T> T generate(LlmCall call, String tag, String prompt, JsonNode schema, int maxTokens,
            Function<JsonNode, T> read);

    /** Runs {@code action} as a phase of the run: its seconds go to the run's stats and to the phase metric. */
    <T> T timed(String phase, Supplier<T> action);

    /** The run's stats: logged and turned into metrics when the run ends; an algorithm adds its own. */
    ObjectNode stats();
}
