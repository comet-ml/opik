package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** The k-means topic algorithm as config.yml sets it up, and a run context that answers the LLM calls from a stub. */
final class TopicsTestAlgorithm {

    private TopicsTestAlgorithm() {
    }

    /** {@code topics.algorithm} with config.yml's defaults. */
    static AlgorithmConfig config() {
        var config = new AlgorithmConfig();
        config.setMinGroups(30);
        config.setMaxGroups(50);
        config.setTextsPerGroup(30);
        config.setKmeansMaxIterations(150);
        config.setKmeansRestarts(3);
        config.setAuthorFrequent(5);
        config.setAuthorBudget(250);
        config.setAuthorMinExamples(5);
        config.setAuthorMaxExamples(10);
        config.setAssignMinCosine(0.75);
        config.setOtherGate(0.95);
        return config;
    }

    static KMeansTopicAlgorithm algorithm(AlgorithmConfig config) {
        return new KMeansTopicAlgorithm(config, new TopicTasks());
    }

    static KMeansTopicAlgorithm algorithm() {
        return algorithm(config());
    }

    /** A run context whose every LLM call gets {@code answer}; it keeps the calls and the run's stats. */
    static final class StubContext implements TopicRunContext {

        record Call(LlmCall call, String tag, String prompt, JsonNode schema, int maxTokens) {
        }

        final List<Call> calls = new ArrayList<>();
        final List<String> phases = new ArrayList<>();
        private final ObjectNode stats = InferenceJson.MAPPER.createObjectNode();
        private final JsonNode answer;

        StubContext(JsonNode answer) {
            this.answer = answer;
        }

        @Override
        public <T> T generate(LlmCall call, String tag, String prompt, JsonNode schema, int maxTokens,
                Function<JsonNode, T> read) {
            calls.add(new Call(call, tag, prompt, schema, maxTokens));
            return read.apply(answer);
        }

        @Override
        public <T> T timed(String phase, Supplier<T> action) {
            phases.add(phase);
            return action.get();
        }

        @Override
        public ObjectNode stats() {
            return stats;
        }
    }
}
