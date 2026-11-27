package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.comet.opik.infrastructure.inference.BatchGenerateItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
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

    /** Part of {@link #labellerFingerprint}: a new version relabels every saved list's window once. */
    static final int LABELLER_VERSION = 1;

    /**
     * Below this many groups a discovery run keeps each trace's cosine to each group centre, {@code n * k} doubles,
     * instead of the window's {@code n * dimensions} floats, which then need not outlive the clustering.
     */
    static final int COSINE_TABLE_BELOW_GROUPS = TopicEmbedding.DIMENSIONS / 2;

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
        // The window's rows are reordered in place for the fit and the examples, not copied: a copy is the heap's
        // largest item on a first build. They are restored before the window is read again.
        var inPlace = IntentMerger.mergeInPlace(window.texts(), window.vectors(), TopicEmbedding.DIMENSIONS);
        var merged = inPlace.merged();
        KMeansResult fit;
        List<List<String>> examples;
        int k;
        int perGroup;
        try {
            k = groups(merged.size());
            var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
            fit = KMeans.fit(merged.vectors(), weights, TopicEmbedding.DIMENSIONS, k, SEED,
                    config.getKmeansMaxIterations(), config.getKmeansRestarts());
            perGroup = authorExampleCount(k);
            int frequent = Math.min(config.getAuthorFrequent(), perGroup);
            examples = GroupSummaries.authorExamples(merged, fit.labels(), fit.centres(), frequent,
                    perGroup - frequent);
        } finally {
            inPlace.restore();
        }
        int unique = merged.size();
        var groupOf = Arrays.stream(merged.textOfTrace()).map(text -> fit.labels()[text]).toArray();
        stats.put("unique_texts", unique);
        stats.put("k", k);
        stats.put("seed", SEED);
        stats.put("kmeans_inertia", fit.inertia());
        stats.put("kmeans_iterations", fit.iterations());

        var groups = GroupSummaries.summarize(groupOf, k);
        stats.put("author_examples", perGroup);
        var task = tasks.author();
        stats.put("author_task_version", task.version());
        var author = LlmRequest.builder()
                .call(LlmCall.AUTHOR)
                .prompt(TopicPrompts.author(task, groups, maxTopics, examples))
                .schema(task.schema())
                .maxTokens(task.effectiveMaxTokens())
                .build();
        // The assignment after the author call compares each trace with group centres only. With fewer groups than
        // COSINE_TABLE_BELOW_GROUPS, those cosines take less memory than the vectors, so the clustering keeps them and
        // the window can go while the call runs.
        var traces = k < COSINE_TABLE_BELOW_GROUPS
                ? AssignmentInput.table(PrototypeAssigner.cosines(window.vectors(), fit.centres(), k,
                        TopicEmbedding.DIMENSIONS), k)
                : AssignmentInput.vectors(window.vectors());
        return new KMeansClustering(traces, maxTopics, groups, fit.centres(), author, stats);
    }

    /**
     * What the assignment reads per trace: its vector, or its cosine to each group centre. Both give the same labels,
     * as {@link PrototypeAssigner#cosines} computes a cosine exactly as the assigner does, and every prototype of a
     * discovery run is a group centre.
     */
    private record AssignmentInput(float[] vectors, double[] table, int groups) {

        static AssignmentInput vectors(float[] vectors) {
            return new AssignmentInput(vectors, null, 0);
        }

        static AssignmentInput table(double[] table, int groups) {
            return new AssignmentInput(null, table, groups);
        }

        int[] assign(PrototypeAssigner assigner, AppliedAnswer applied) {
            if (vectors != null) {
                return assigner.assign(vectors);
            }
            var topicGroups = applied.topics().stream().flatMap(topic -> topic.groups().stream())
                    .mapToInt(Integer::intValue).toArray();
            var otherGroups = applied.other().stream().mapToInt(Integer::intValue).toArray();
            return assigner.assign(table, groups, topicGroups, otherGroups);
        }
    }

    /** The groups of one window, waiting for the author answer. */
    @RequiredArgsConstructor
    private final class KMeansClustering implements Clustering {

        private final AssignmentInput traces;
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
                    .labels(traces.assign(assigner, applied))
                    .otherPrototypes(assigner.keptOther())
                    .stats(finishStats)
                    .build();
        }
    }

    /**
     * {@link NightlyUpdate} over the saved list. A naming answer that is not a JSON object (null) leaves its groups in
     * Other. A failed call, and a verify answer that is not a JSON object, leave their groups in Other tonight only
     * ({@code other_failed}).
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

    /**
     * A hash of everything {@link #labeller} labels by: the topic ids and their vectors in list order, the known-Other
     * vectors, {@code assignMinCosine} and {@code otherGate}. Bump {@link #LABELLER_VERSION} when the assignment's
     * code changes what it returns.
     */
    @Override
    public String labellerFingerprint(@NonNull List<Topic> topics, @Nullable PrototypeVectors otherVectors) {
        var hasher = Hashing.sha256().newHasher()
                .putInt(LABELLER_VERSION)
                .putDouble(config.getAssignMinCosine())
                .putDouble(config.getOtherGate())
                .putInt(topics.size());
        for (var topic : topics) {
            hasher.putLong(topic.id().getMostSignificantBits()).putLong(topic.id().getLeastSignificantBits());
            putVectors(hasher, topic.vectors().vectors());
        }
        putVectors(hasher, otherVectors == null ? List.of() : otherVectors.vectors());
        return hasher.hash().toString();
    }

    private static void putVectors(Hasher hasher, List<float[]> vectors) {
        hasher.putInt(vectors.size());
        for (var vector : vectors) {
            for (float value : vector) {
                hasher.putInt(Float.floatToRawIntBits(value));
            }
        }
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
