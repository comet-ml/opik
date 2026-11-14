package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.topics.TopicsRebuildService.DIMENSIONS;

/**
 * The topic algorithm in use. A first build merges identical texts, k-means them into fine groups, has Qwen name topics
 * over the groups, and assigns every trace to its nearest prototype with {@link PrototypeAssigner}: the topic
 * prototypes are the centres of each topic's groups, the known-Other ones the centres of the Other groups. A nightly
 * update is {@link NightlyUpdate}. The labeller applies the same assigner to a saved list.
 */
@Singleton
public class KMeansTopicAlgorithm implements TopicAlgorithm {

    /** The first build's fine groups: one per {@value #TEXTS_PER_GROUP} unique texts, within these bounds. */
    static final int MIN_GROUPS = 30;
    static final int MAX_GROUPS = 50;
    static final int TEXTS_PER_GROUP = 30;
    /**
     * The author sees up to {@value #AUTHOR_FREQUENT} repeated texts per group, then the ones nearest its centre, in
     * all clamp(round({@value #AUTHOR_BUDGET} / k), {@value #AUTHOR_MIN}, {@value #AUTHOR_MAX}) texts, so the prompt
     * stays about as long as the longest one known to work (5 texts on 50 groups).
     */
    static final int AUTHOR_FREQUENT = 5;
    static final int AUTHOR_BUDGET = 250;
    static final int AUTHOR_MIN = 5;
    static final int AUTHOR_MAX = 10;
    static final int AUTHOR_MAX_TOKENS = 2500;
    static final String AUTHOR_TAG = "author";

    /**
     * The first build's number of fine groups: min({@value #MAX_GROUPS}, max({@value #MIN_GROUPS}, unique /
     * {@value #TEXTS_PER_GROUP})), at most the unique texts.
     */
    static int groups(int unique) {
        return Math.min(Math.min(MAX_GROUPS, Math.max(MIN_GROUPS, unique / TEXTS_PER_GROUP)), unique);
    }

    /** How many texts the author sees per group for {@code k} groups. */
    static int authorExampleCount(int k) {
        return Math.min(AUTHOR_MAX, Math.max(AUTHOR_MIN, (int) Math.round((double) AUTHOR_BUDGET / k)));
    }

    @Override
    public FirstBuild firstBuild(@NonNull Window window, int maxTopics, @NonNull Context context) {
        int n = window.size();
        var texts = window.texts();
        var vectors = window.vectors();
        var stats = context.stats();

        var merged = IntentMerger.merge(texts, vectors, DIMENSIONS);
        int unique = merged.size();
        int k = groups(unique);
        long seed = 0;
        var weights = new double[unique];
        for (int u = 0; u < unique; u++) {
            weights[u] = merged.weights()[u];
        }
        var fit = context.timed("kmeans", () -> KMeans.fit(merged.vectors(), weights, DIMENSIONS, k, seed));
        var groupOf = new int[n];
        for (int i = 0; i < n; i++) {
            groupOf[i] = fit.labels()[merged.inverse()[i]];
        }
        stats.put("unique_texts", unique);
        stats.put("k", k);
        stats.put("seed", seed);
        stats.put("kmeans_inertia", fit.inertia());
        stats.put("kmeans_iterations", fit.iterations());

        var groups = GroupSummaries.summarize(groupOf, k);
        int perGroup = authorExampleCount(k);
        stats.put("author_examples", perGroup);
        var examples = GroupSummaries.authorExamples(texts, vectors, groupOf, fit.centres(), DIMENSIONS,
                Math.min(AUTHOR_FREQUENT, perGroup), Math.max(0, perGroup - AUTHOR_FREQUENT));
        var prompt = TopicPrompts.author(groups, maxTopics, examples);
        var answer = context.generate(Call.AUTHOR, AUTHOR_TAG, prompt, TopicPrompts.AUTHOR_SCHEMA, AUTHOR_MAX_TOKENS,
                AuthorAnswer::parse);
        var applied = AuthorApplier.apply(answer, groups, maxTopics);

        return context.timed("assign", () -> {
            var topicPrototypes = new ArrayList<float[]>();
            var owners = new ArrayList<Integer>();
            for (int t = 0; t < applied.topics().size(); t++) {
                for (int group : applied.topics().get(t).groups()) {
                    topicPrototypes.add(centre(fit.centres(), group));
                    owners.add(t);
                }
            }
            var otherPrototypes = applied.other().stream().map(group -> centre(fit.centres(), group)).toList();
            var assigner = new PrototypeAssigner(topicPrototypes, owners, otherPrototypes, DIMENSIONS);
            var labels = assigner.assign(vectors);
            stats.put("other_groups", applied.other().size());
            stats.put("gated_other", assigner.gatedOther());
            // A gated known-Other prototype is not returned: the assigner leaves it out as long as the topic prototype
            // near it is saved, so it would assign no trace.
            return FirstBuild.builder()
                    .topics(applied.topics().stream()
                            .map(topic -> NewTopic.builder().name(topic.name()).definition(topic.definition())
                                    .build())
                            .toList())
                    .labels(labels)
                    .topicPrototypes(topicPrototypes)
                    .owners(owners)
                    .otherPrototypes(assigner.keptOther())
                    .build();
        });
    }

    /**
     * {@link NightlyUpdate} over the saved list. Its naming and verify calls go through {@code context}; an answer that
     * is not a JSON object counts as {@code bad_answer} and leaves its groups in Other.
     */
    @Override
    public Update update(@NonNull NightState state, @NonNull NightWindow window, int maxTopics,
            @NonNull Context context) {
        var result = NightlyUpdate.run(state, window, maxTopics, (tag, prompt, schema, maxTokens) -> {
            var call = ForcedBirth.VERIFY_TAG.equals(tag) ? Call.VERIFY : Call.NAMING;
            var answer = context.generate(call, tag, prompt, schema, maxTokens, NightJson::object);
            return answer == null ? NullNode.getInstance() : answer;
        });
        var stats = context.stats();
        stats.put("unassigned", result.unassigned());
        stats.put("candidates", result.candidates().size());
        stats.put("big", result.big().size());
        var forced = stats.putObject("forced");
        result.forced().forEach(outcome -> {
            var key = outcome.result().name().toLowerCase(Locale.ROOT);
            forced.put(key, forced.path(key).asInt() + 1);
        });
        return Update.builder().before(result.before()).labels(result.labels()).build();
    }

    /**
     * The {@link PrototypeAssigner} over the saved rows, as the build made it: the topic prototypes in position order,
     * each topic's index the order of its first row, then the known-Other ones.
     */
    @Override
    public Optional<Labeller> labeller(@NonNull List<TopicPrototype> prototypes) {
        var topicIds = new ArrayList<UUID>();
        var topicPrototypes = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        var otherPrototypes = new ArrayList<float[]>();
        for (var prototype : prototypes) {
            if (prototype.topicId() == null) {
                otherPrototypes.add(prototype.values());
                continue;
            }
            int owner = topicIds.indexOf(prototype.topicId());
            if (owner < 0) {
                topicIds.add(prototype.topicId());
                owner = topicIds.size() - 1;
            }
            topicPrototypes.add(prototype.values());
            owners.add(owner);
        }
        if (topicPrototypes.isEmpty()) {
            return Optional.empty();
        }
        var assigner = new PrototypeAssigner(topicPrototypes, owners, otherPrototypes, DIMENSIONS);
        var ids = List.copyOf(topicIds);
        return Optional.of(vectors -> {
            var labels = assigner.assign(vectors);
            var out = new UUID[labels.length];
            Arrays.setAll(out, i -> labels[i] == OTHER ? TraceTopic.OTHER : ids.get(labels[i]));
            return out;
        });
    }

    private static float[] centre(float[] centres, int group) {
        var centre = new float[DIMENSIONS];
        System.arraycopy(centres, group * DIMENSIONS, centre, 0, DIMENSIONS);
        return centre;
    }
}
