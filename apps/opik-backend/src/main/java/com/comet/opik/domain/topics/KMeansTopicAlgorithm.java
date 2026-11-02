package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The topic algorithm of stages 2a, 2b and 3. The first build merges identical texts, k-means them into fine groups,
 * has the LLM name topics over the groups and assigns every trace to its nearest group centre. The nightly update is
 * {@link NightlyUpdate}, with {@link NightClusterer#KMEANS}. Both end with {@link PrototypeAssigner} (cosine
 * {@value PrototypeAssigner#ASSIGN_MIN_COSINE}, Other gate {@value PrototypeAssigner#OTHER_GATE}), and so does arrival,
 * with the prototypes the night saved: a trace's topic on arrival is the one the night would give it. Stateless.
 */
@Singleton
public class KMeansTopicAlgorithm implements TopicAlgorithm {

    static final int MIN_GROUPS = 15;
    static final int MAX_GROUPS = 50;
    static final int TEXTS_PER_GROUP = 60;
    static final int QWEN_MAX_TOKENS = 2500;
    static final String AUTHOR_TAG = "author";

    @Override
    public FirstBuild firstBuild(@NonNull NightWindow window, int maxTopics, @NonNull Context context) {
        int n = window.size();
        int d = window.dimensions();
        var stats = context.stats();
        var merged = IntentMerger.merge(window.texts(), window.vectors(), d);
        int unique = merged.size();
        int k = Math.min(Math.min(MAX_GROUPS, Math.max(MIN_GROUPS, unique / TEXTS_PER_GROUP)), unique);
        // The seed is the project's saved nights: 0 for the first build.
        long seed = 0;
        var weights = new double[unique];
        for (int u = 0; u < unique; u++) {
            weights[u] = merged.weights()[u];
        }
        var fit = context.timed("kmeans", () -> KMeans.fit(merged.vectors(), weights, d, k, seed));
        var groupOf = new int[n];
        for (int i = 0; i < n; i++) {
            groupOf[i] = fit.labels()[merged.inverse()[i]];
        }
        stats.put("unique_texts", unique);
        stats.put("k", k);
        stats.put("seed", seed);
        stats.put("kmeans_inertia", fit.inertia());
        stats.put("kmeans_iterations", fit.iterations());

        var groups = GroupSummaries.summarize(window.texts(), window.vectors(), groupOf, fit.centres(), window.recent(),
                d);
        var prompt = TopicPrompts.author(groups, maxTopics);
        var answer = context.timed("qwen", () -> context.generate(AUTHOR_TAG, prompt, TopicPrompts.AUTHOR_SCHEMA,
                QWEN_MAX_TOKENS, AuthorAnswer::parse));
        var applied = AuthorApplier.apply(answer, groups, maxTopics);

        var built = context.timed("assign", () -> assign(applied, fit.centres(), window.vectors(), d));
        stats.put("other_groups", applied.other().size());
        stats.put("gated_other", built.gatedOther());
        return built.firstBuild();
    }

    @Override
    public Update update(@NonNull NightState state, @NonNull NightWindow window, int maxTopics,
            @NonNull Context context) {
        var result = NightlyUpdate.run(state, window, maxTopics, NightClusterer.KMEANS,
                (tag, prompt, schema, maxTokens) -> context.generate(tag, prompt, schema, maxTokens, answer -> answer));
        var stats = context.stats();
        stats.put("review_night", result.reviewNight());
        stats.put("review", result.review());
        stats.put("unassigned", result.unassigned());
        stats.put("candidates", result.candidates().size());
        stats.put("big", result.big().size());
        if (result.verified() != null) {
            stats.put("verify_pairs", result.verified().pairs().size());
            stats.put("verify_failed", result.verified().failed());
        }
        if (result.forced() != null) {
            var forced = stats.putObject("forced");
            result.forced().forEach(outcome -> forced.put(outcome.result().name().toLowerCase(Locale.ROOT),
                    forced.path(outcome.result().name().toLowerCase(Locale.ROOT)).asInt() + 1));
        }
        var revived = stats.putArray("revived");
        result.revived().forEach(t -> revived.add(state.topic(t).getName()));
        return Update.builder().labels(result.labels()).build();
    }

    /**
     * The assignment by {@code live}'s prototypes and the known-Other ones, each in {@code prototypes}' order: the order
     * the night saved them in and assigned with, so a tie goes the same way. The prototypes a nightly update keeps for
     * dormant or retired topics it may revive are left out: only a night brings such a topic back. Empty without a
     * prototype of a live topic.
     */
    @Override
    public Optional<Arrival> arrival(@NonNull List<Topic> live, @NonNull List<TopicPrototype> prototypes) {
        var owners = new HashMap<UUID, Integer>();
        for (var topic : live) {
            owners.put(topic.id(), owners.size());
        }
        var topicPrototypes = new ArrayList<float[]>();
        var topicOwners = new ArrayList<Integer>();
        var otherPrototypes = new ArrayList<float[]>();
        for (var row : prototypes) {
            if (row.topicId() == null) {
                otherPrototypes.add(row.values());
            } else if (owners.containsKey(row.topicId())) {
                topicPrototypes.add(row.values());
                topicOwners.add(owners.get(row.topicId()));
            }
        }
        if (topicPrototypes.isEmpty()) {
            return Optional.empty();
        }
        int dimensions = topicPrototypes.getFirst().length;
        Preconditions.checkState(Stream.concat(topicPrototypes.stream(), otherPrototypes.stream())
                .allMatch(values -> values.length == dimensions), "prototypes of more than one length");
        return Optional.of(new NearestPrototype(
                new PrototypeAssigner(topicPrototypes, topicOwners, otherPrototypes, dimensions),
                live.stream().map(Topic::id).toList(), dimensions));
    }

    /** Arrival by the saved prototypes, with the topic id of each owner index the assigner returns. */
    private record NearestPrototype(PrototypeAssigner assigner, List<UUID> topicIds,
            int dimensions) implements Arrival {

        @Override
        public UUID[] label(@NonNull float[] vectors) {
            var owners = assigner.assign(vectors);
            var topics = new UUID[owners.length];
            for (int i = 0; i < owners.length; i++) {
                topics[i] = owners[i] == PrototypeAssigner.OTHER ? TraceTopic.OTHER : topicIds.get(owners[i]);
            }
            return topics;
        }
    }

    /** The assignment by the group centres, and how many known-Other prototypes its gate left out. */
    private record Built(FirstBuild firstBuild, int gatedOther) {
    }

    private static Built assign(AuthorApplier.Applied applied, float[] centres, float[] vectors, int d) {
        var topicPrototypes = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t = 0; t < applied.topics().size(); t++) {
            for (int group : applied.topics().get(t).groups()) {
                topicPrototypes.add(centre(centres, group, d));
                owners.add(t);
            }
        }
        var otherPrototypes = applied.other().stream().map(group -> centre(centres, group, d)).toList();
        var assigner = new PrototypeAssigner(topicPrototypes, owners, otherPrototypes, d);
        var firstBuild = FirstBuild.builder()
                .topics(applied.topics().stream()
                        .map(topic -> NewTopic.builder().name(topic.name()).definition(topic.definition()).build())
                        .toList())
                .labels(assigner.assign(vectors))
                .topicPrototypes(topicPrototypes)
                .owners(owners)
                .otherPrototypes(otherPrototypes)
                .build();
        return new Built(firstBuild, assigner.gatedOther());
    }

    private static float[] centre(float[] centres, int group, int d) {
        var centre = new float[d];
        System.arraycopy(centres, group * d, centre, 0, d);
        return centre;
    }
}
