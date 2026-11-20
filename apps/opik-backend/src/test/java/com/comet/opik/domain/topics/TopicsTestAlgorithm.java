package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** The k-means topic algorithm as config.yml sets it up, and a run context that answers the LLM calls from a stub. */
final class TopicsTestAlgorithm {

    static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

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
        var topics = new TopicsConfig();
        topics.setAlgorithm(config);
        return new KMeansTopicAlgorithm(topics, new TopicTasks());
    }

    static KMeansTopicAlgorithm algorithm() {
        return algorithm(config());
    }

    /** A saved topic of the project with a random id, name, definition and birth date; the caller sets its vectors. */
    static Topic.TopicBuilder topic(String workspaceId, UUID projectId) {
        return Topic.builder()
                .id(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .definition(RandomStringUtils.secure().nextAlphanumeric(30))
                .born(LocalDate.now().minusDays(RandomUtils.secure().randomInt(0, 365)));
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
