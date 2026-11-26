package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.comet.opik.infrastructure.inference.BatchGenerateItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
    /** The rename prompt's examples of a merged topic: the first few of each of its groups', up to a cap. */
    private static final int MERGE_EXAMPLES_PER_GROUP = 4;
    private static final int MERGE_EXAMPLES = 16;

    private final AlgorithmConfig config;
    private final TopicTasks tasks;

    @Inject
    public KMeansTopicAlgorithm(@NonNull @Config("topics") TopicsConfig topics, @NonNull TopicTasks tasks) {
        this.config = Objects.requireNonNull(topics.getAlgorithm(), "topics.algorithm");
        this.tasks = tasks;
        // A night verifies at most nightlyMaxGroups pairs in one call. Checked here, where the task and the config are
        // both known: the inference service rejects a call above its limit, so every such night would fail.
        int verifyMaxTokens = Verifier.maxTokens(tasks.verify(), config, config.getNightlyMaxGroups());
        Preconditions.checkState(verifyMaxTokens <= BatchGenerateItem.MAX_MAX_TOKENS,
                "the verify call's max_tokens (max_tokens of topics/tasks/verify.yaml + "
                        + "topics.algorithm.nightlyVerifyTokensPerPair x nightlyMaxGroups) is above the limit, "
                        + "limit '%s', max_tokens '%s'",
                BatchGenerateItem.MAX_MAX_TOKENS, verifyMaxTokens);
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
        return new KMeansClustering(window.vectors(), maxTopics, groups, examples, fit.centres(), author, stats);
    }

    /** The groups of one window, waiting for the author answer. */
    @RequiredArgsConstructor
    private final class KMeansClustering implements Clustering {

        private final float[] vectors;
        private final int maxTopics;
        private final List<GroupSummary> groups;
        private final List<List<String>> examples;
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
        public NightStep<DiscoveredTopics> finish(@NonNull AuthorAnswer answer) {
            var applied = AuthorApplier.apply(answer, groups, maxTopics);
            var topics = applied.topics().stream()
                    .map(topic -> NewTopic.builder()
                            .name(topic.name())
                            .definition(topic.definition())
                            .prototypes(topic.groups().stream().map(group -> centre(centres, group)).toList())
                            .build())
                    .toList();
            var otherPrototypes = applied.other().stream().map(group -> centre(centres, group)).toList();
            var merges = TopicMerger.merges(vectors, assigner(topics, otherPrototypes).assign(vectors), topics.size(),
                    IntStream.range(0, topics.size()).toArray(), TopicEmbedding.DIMENSIONS, config.getMergeCosine());
            var finishStats = JsonUtils.createObjectNode();
            finishStats.put("merged", merges.size());
            finishStats.put("other_groups", applied.other().size());
            if (merges.isEmpty()) {
                return NightStep.done(discovered(topics, otherPrototypes, finishStats));
            }
            var roots = TopicMerger.roots(topics.size(), merges);
            var kept = IntStream.range(0, topics.size()).filter(t -> roots[t] == t).toArray();
            var renamed = Arrays.stream(kept)
                    .filter(root -> IntStream.range(0, topics.size()).anyMatch(t -> t != root && roots[t] == root))
                    .boxed().toList();
            var merged = renamed.stream().map(root -> merged(applied.topics(), roots, root)).toList();
            var taken = Arrays.stream(kept).filter(t -> !renamed.contains(t)).mapToObj(t -> topics.get(t).name())
                    .collect(Collectors.toSet());
            return TopicMerger.rename(merged, taken, tasks).map(names -> {
                var out = new ArrayList<NewTopic>();
                for (int t : kept) {
                    var prototypes = IntStream.range(0, topics.size()).filter(u -> roots[u] == t)
                            .boxed().sorted(Comparator.comparingInt(u -> u == t ? -1 : u))
                            .flatMap(u -> topics.get(u).prototypes().stream()).toList();
                    int m = renamed.indexOf(t);
                    var topic = topics.get(t).toBuilder().prototypes(prototypes);
                    out.add(m < 0
                            ? topic.build()
                            : topic.name(names.get(m).name()).definition(names.get(m).definition()).build());
                }
                return discovered(out, otherPrototypes, finishStats);
            });
        }

        /** A merged topic for the rename call: {@code root} first, then the topics folded into it. */
        private TopicMerger.Merged merged(List<AppliedTopic> applied, int[] roots, int root) {
            var members = IntStream.range(0, roots.length).filter(t -> roots[t] == root).boxed()
                    .sorted(Comparator.comparingInt(t -> t == root ? -1 : t)).toList();
            var groupsOf = members.stream().flatMap(t -> applied.get(t).groups().stream()).toList();
            var byGroup = new HashMap<Integer, GroupSummary>();
            groups.forEach(group -> byGroup.put(group.group(), group));
            return new TopicMerger.Merged(
                    members.stream().map(t -> applied.get(t).name()).toList(),
                    members.stream().map(t -> applied.get(t).definition()).toList(),
                    groupsOf.stream().mapToDouble(group -> byGroup.get(group).share()).sum(),
                    groupsOf.stream().flatMap(group -> examples.get(group).stream().limit(MERGE_EXAMPLES_PER_GROUP))
                            .limit(MERGE_EXAMPLES).toList());
        }

        private DiscoveredTopics discovered(List<NewTopic> topics, List<float[]> otherPrototypes,
                ObjectNode finishStats) {
            var assigner = assigner(topics, otherPrototypes);
            var labels = assigner.assign(vectors);
            finishStats.put("gated_other", assigner.gatedOther());
            // A gated known-Other prototype is not returned: the assigner leaves it out as long as the topic prototype
            // near it is saved, so it would assign no trace.
            return DiscoveredTopics.builder()
                    .topics(topics)
                    .labels(labels)
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
    public TopicLabeller labeller(@NonNull List<Topic> topics, @Nullable PrototypeVectors otherVectors) {
        if (topics.isEmpty()) {
            return vectors -> vectors.stream().map(vector -> TopicEmbedding.usable(vector) ? TraceTopic.OTHER : null)
                    .toArray(UUID[]::new);
        }
        var assigner = assigner(
                topics.stream().flatMap(topic -> topic.vectors().vectors().stream()).toList(),
                ownerOfEach(topics.stream().map(topic -> topic.vectors().size()).toList()),
                otherVectors == null ? List.of() : otherVectors.vectors());
        var ids = topics.stream().map(Topic::id).toList();
        return vectors -> {
            var usableIndexes = IntStream.range(0, vectors.size()).filter(i -> TopicEmbedding.usable(vectors.get(i)))
                    .toArray();
            int d = TopicEmbedding.DIMENSIONS;
            var flat = new float[usableIndexes.length * d];
            for (int i = 0; i < usableIndexes.length; i++) {
                System.arraycopy(vectors.get(usableIndexes[i]), 0, flat, i * d, d);
            }
            var labels = assigner.assign(flat);
            var out = new UUID[vectors.size()];
            for (int i = 0; i < usableIndexes.length; i++) {
                out[usableIndexes[i]] = labels[i] == OTHER ? TraceTopic.OTHER : ids.get(labels[i]);
            }
            return out;
        };
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

    private PrototypeAssigner assigner(List<NewTopic> topics, List<float[]> otherPrototypes) {
        return assigner(topics.stream().flatMap(topic -> topic.prototypes().stream()).toList(),
                ownerOfEach(topics.stream().map(topic -> topic.prototypes().size()).toList()), otherPrototypes);
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
