package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The topic algorithm in use. A discovery run merges identical texts, k-means them into fine groups, has the LLM name
 * topics over the groups ({@link TopicTasks#author()}), and assigns every trace to its nearest prototype with
 * {@link PrototypeAssigner}: the topic prototypes are the centres of each topic's groups, the known-Other ones the
 * centres of the Other groups. A nightly update is {@link NightlyUpdate}. The labeller applies the same assigner to a
 * saved list. Its tunables are {@code topics.algorithm}.
 */
@Singleton
public class KMeansTopicAlgorithm implements TopicAlgorithm {

    /** The k-means seed: fixed, so the same window always gives the same groups. */
    static final long SEED = 0;

    private final AlgorithmConfig config;
    private final TopicTasks tasks;

    @Inject
    public KMeansTopicAlgorithm(@NonNull @Config("topics") TopicsConfig topics, @NonNull TopicTasks tasks) {
        this.config = Objects.requireNonNull(topics.getAlgorithm(), "topics.algorithm");
        this.tasks = tasks;
    }

    @Override
    public Clustering cluster(@NonNull TopicWindow window, int maxTopics) {
        var stats = JsonUtils.createObjectNode();
        var merged = IntentMerger.merge(window.texts(), window.vectors(), TopicEmbedding.DIMENSIONS);
        int unique = merged.size();
        int k = groups(unique);
        var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
        var fit = KMeans.fit(merged.vectors(), weights, TopicEmbedding.DIMENSIONS, k, SEED,
                config.getKmeansMaxIterations(), config.getKmeansRestarts());
        var groupOf = Arrays.stream(merged.textOfTrace()).map(text -> fit.labels()[text]).toArray();
        stats.put("unique_texts", unique);
        stats.put("k", k);
        stats.put("seed", SEED);
        stats.put("kmeans_inertia", fit.inertia());
        stats.put("kmeans_iterations", fit.iterations());

        var groups = GroupSummaries.summarize(groupOf, k);
        int perGroup = authorExampleCount(k);
        int frequent = Math.min(config.getAuthorFrequent(), perGroup);
        stats.put("author_examples", perGroup);
        var examples = GroupSummaries.authorExamples(merged, fit.labels(), fit.centres(), frequent,
                perGroup - frequent);
        var task = tasks.author();
        stats.put("author_task_version", task.version());
        var author = LlmRequest.builder()
                .call(LlmCall.AUTHOR)
                .prompt(TopicPrompts.author(task, groups, maxTopics, examples))
                .schema(task.schema())
                .maxTokens(task.effectiveMaxTokens())
                .build();
        return new KMeansClustering(window.vectors(), maxTopics, groups, fit.centres(), author, stats);
    }

    /** The groups of one window, waiting for the author answer. */
    @RequiredArgsConstructor
    private final class KMeansClustering implements Clustering {

        private final float[] vectors;
        private final int maxTopics;
        private final List<GroupSummary> groups;
        private final float[] centres;
        private final LlmRequest author;
        private final ObjectNode stats;

        @Override
        public LlmRequest author() {
            return author;
        }

        @Override
        public ObjectNode stats() {
            return stats;
        }

        @Override
        public DiscoveredTopics finish(@NonNull AuthorAnswer answer) {
            var applied = AuthorApplier.apply(answer, groups, maxTopics);
            var topics = applied.topics().stream()
                    .map(topic -> NewTopic.builder()
                            .name(topic.name())
                            .definition(topic.definition())
                            .prototypes(topic.groups().stream().map(group -> centre(centres, group)).toList())
                            .build())
                    .toList();
            var topicPrototypes = topics.stream().flatMap(topic -> topic.prototypes().stream()).toList();
            var owners = ownerOfEach(topics.stream().map(topic -> topic.prototypes().size()).toList());
            var otherPrototypes = applied.other().stream().map(group -> centre(centres, group)).toList();
            var assigner = assigner(topicPrototypes, owners, otherPrototypes);
            var finishStats = JsonUtils.createObjectNode();
            finishStats.put("other_groups", applied.other().size());
            finishStats.put("gated_other", assigner.gatedOther());
            // A gated known-Other prototype is not returned: the assigner leaves it out as long as the topic prototype
            // near it is saved, so it would assign no trace.
            return DiscoveredTopics.builder()
                    .topics(topics)
                    .labels(assigner.assign(vectors))
                    .otherPrototypes(assigner.keptOther())
                    .stats(finishStats)
                    .build();
        }
    }

    /**
     * {@link NightlyUpdate} over the saved list. An answer that is not a JSON object (null) leaves its groups in Other;
     * for the verify call, it verifies no pair.
     */
    @Override
    public NightStep<TopicUpdate> update(@NonNull NightState state, @NonNull NightWindow window, int maxTopics) {
        return NightlyUpdate.run(state, window, maxTopics, config, tasks).map(result -> {
            var stats = JsonUtils.createObjectNode();
            stats.put("naming_task_version", tasks.naming().version());
            stats.put("naming_new_task_version", tasks.namingNew().version());
            stats.put("verify_task_version", tasks.verify().version());
            stats.put("unassigned", result.unassigned());
            stats.put("candidates", result.candidates().size());
            stats.put("big", result.big().size());
            var forced = stats.putObject("forced");
            result.forced().forEach(outcome -> {
                var key = outcome.result().name().toLowerCase(Locale.ROOT);
                forced.put(key, forced.path(key).asInt() + 1);
            });
            return TopicUpdate.builder().before(result.before()).labels(result.labels()).stats(stats).build();
        });
    }

    /**
     * The {@link PrototypeAssigner} over the saved list, as the run made it: each topic's vectors in topic order,
     * owned by the topic's index, then the known-Other ones.
     */
    @Override
    public Optional<TopicLabeller> labeller(@NonNull List<Topic> topics, @Nullable PrototypeVectors otherVectors) {
        if (topics.isEmpty()) {
            return Optional.empty();
        }
        var assigner = assigner(
                topics.stream().flatMap(topic -> topic.vectors().vectors().stream()).toList(),
                ownerOfEach(topics.stream().map(topic -> topic.vectors().size()).toList()),
                otherVectors == null ? List.of() : otherVectors.vectors());
        var ids = topics.stream().map(Topic::id).toList();
        return Optional.of(vectors -> Arrays.stream(assigner.assign(vectors))
                .mapToObj(label -> label == OTHER ? TraceTopic.OTHER : ids.get(label))
                .toArray(UUID[]::new));
    }

    /** One group per {@code textsPerGroup} unique texts, within the configured bounds and never more than the texts. */
    private int groups(int unique) {
        return KMeans.groupCount(unique, config.getTextsPerGroup(), config.getMinGroups(), config.getMaxGroups(),
                unique);
    }

    /** {@code clamp(round(authorBudget / k), authorMinExamples, authorMaxExamples)}. */
    private int authorExampleCount(int k) {
        return Math.min(config.getAuthorMaxExamples(), Math.max(config.getAuthorMinExamples(),
                (int) Math.round((double) config.getAuthorBudget() / k)));
    }

    private PrototypeAssigner assigner(List<float[]> topicPrototypes, List<Integer> owners,
            List<float[]> otherPrototypes) {
        return new PrototypeAssigner(topicPrototypes, owners, otherPrototypes, TopicEmbedding.DIMENSIONS,
                config.getAssignMinCosine(), config.getOtherGate());
    }

    /** Topic index {@code t} repeated {@code counts.get(t)} times, in topic order: the owner of each prototype. */
    private static List<Integer> ownerOfEach(List<Integer> counts) {
        return IntStream.range(0, counts.size()).boxed()
                .flatMap(t -> Collections.nCopies(counts.get(t), t).stream())
                .toList();
    }

    private static float[] centre(float[] centres, int group) {
        return Arrays.copyOfRange(centres, group * TopicEmbedding.DIMENSIONS, (group + 1) * TopicEmbedding.DIMENSIONS);
    }
}
