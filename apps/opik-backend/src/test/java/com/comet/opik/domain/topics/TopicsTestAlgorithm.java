package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;

/** The k-means topic algorithm as config.yml sets it up. */
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
        config.setMaxTopics(10);
        config.setProvisionalBelow(1_000);
        return config;
    }

    /** {@link #config()} with exactly {@code k} groups, as the fixture's reference grouping has. */
    static AlgorithmConfig fixedGroups(int k) {
        var config = config();
        config.setMinGroups(k);
        config.setMaxGroups(k);
        return config;
    }

    static KMeansTopicAlgorithm algorithm(AlgorithmConfig config) {
        return new KMeansTopicAlgorithm(config, new TopicTasks());
    }

    static KMeansTopicAlgorithm algorithm() {
        return algorithm(config());
    }

}
