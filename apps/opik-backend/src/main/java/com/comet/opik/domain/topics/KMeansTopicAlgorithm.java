package com.comet.opik.domain.topics;

import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The topic algorithm of stages 2a and 2b. The first build merges identical texts, k-means them into fine groups, has
 * the LLM name topics over the groups and assigns every trace to its nearest group centre. The nightly update is
 * {@link NightlyUpdate}, with {@link NightClusterer#KMEANS}. Stateless.
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
        return new Update(result.labels());
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
                .topics(applied.topics().stream().map(topic -> new NewTopic(topic.name(), topic.definition()))
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
