package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.time.LocalDate;
import java.util.UUID;

/** The k-means topic algorithm as config.yml sets it up. */
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
        config.setMergeCosine(0.96);
        config.setMaxTopics(10);
        config.setProvisionalBelow(1_000);
        config.setNightlyMinCandidate(20);
        config.setNightlyMinGroups(2);
        config.setNightlyMaxGroups(20);
        config.setNightlyTextsPerGroup(40);
        config.setNightlyFrequent(3);
        config.setNightlyExamples(5);
        config.setRecentDays(7);
        config.setRetireShare(0.005);
        config.setRetireNights(3);
        config.setNightlyMinTightness(0.77);
        config.setNightlyBigShare(0.01);
        config.setNightlySameMemory(0.95);
        config.setNightlyVerifyTokensPerPair(25);
        config.setAuthorTokensPerTopic(60);
        config.setAuthorTokensPerGroup(20);
        config.setNightlyTopicExamples(4);
        config.setVerifyTopicExamples(3);
        config.setVerifyGroupExamples(5);
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
        var topics = new TopicsConfig();
        topics.setAlgorithm(config);
        return new KMeansTopicAlgorithm(topics, new TopicTasks());
    }

    /** {@code step}'s result, every call it asks answered badly: a rename keeps the larger topic's name. */
    static <T> T finished(NightStep<T> step) {
        while (step instanceof NightStep.Ask<T> ask) {
            step = ask.next().apply(null);
        }
        return ((NightStep.Done<T>) step).result();
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

}
