package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The topic algorithm in use. A first build merges identical texts, k-means them into fine groups, has the LLM name
 * topics over the groups ({@link TopicTasks#author()}), and assigns every trace to its nearest prototype with
 * {@link PrototypeAssigner}: the topic prototypes are the centres of each topic's groups, the known-Other ones the
 * centres of the Other groups. The labeller applies the same assigner to a saved list. Its tunables are
 * {@code topics.algorithm}.
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
    public FirstBuild firstBuild(@NonNull TopicWindow window, int maxTopics, @NonNull TopicRunContext context) {
        var stats = context.stats();
        var merged = IntentMerger.merge(window.texts(), window.vectors(), TopicEmbedding.DIMENSIONS);
        int unique = merged.size();
        int k = groups(unique);
        var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
        var fit = context.timed("kmeans", () -> KMeans.fit(merged.vectors(), weights, TopicEmbedding.DIMENSIONS, k,
                SEED, config.getKmeansMaxIterations(), config.getKmeansRestarts()));
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
        var answer = context.generate(LlmCall.AUTHOR, LlmCall.AUTHOR.getValue(),
                TopicPrompts.author(task, groups, maxTopics, examples), task.schema(), task.effectiveMaxTokens(),
                AuthorAnswer::parse);
        var applied = AuthorApplier.apply(answer, groups, maxTopics);

        return context.timed("assign", () -> {
            var topicPrototypes = applied.topics().stream()
                    .flatMap(topic -> topic.groups().stream())
                    .map(group -> centre(fit.centres(), group))
                    .toList();
            var owners = ownerOfEach(applied.topics().stream().map(topic -> topic.groups().size()).toList());
            var otherPrototypes = applied.other().stream().map(group -> centre(fit.centres(), group)).toList();
            var assigner = assigner(topicPrototypes, owners, otherPrototypes);
            stats.put("other_groups", applied.other().size());
            stats.put("gated_other", assigner.gatedOther());
            // A gated known-Other prototype is not returned: the assigner leaves it out as long as the topic prototype
            // near it is saved, so it would assign no trace.
            return FirstBuild.builder()
                    .topics(applied.topics().stream()
                            .map(topic -> NewTopic.builder().name(topic.name()).definition(topic.definition())
                                    .build())
                            .toList())
                    .labels(assigner.assign(window.vectors()))
                    .topicPrototypes(topicPrototypes)
                    .owners(owners)
                    .otherPrototypes(assigner.keptOther())
                    .build();
        });
    }

    /**
     * The {@link PrototypeAssigner} over the saved list, as the build made it: each topic's vectors in topic order,
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
