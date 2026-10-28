package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Step a2 of the nightly update, before step b: a dormant topic whose traffic came back is made live again, so the
 * night does not create a second topic for the same theme. Every window trace goes to its nearest prototype among the
 * live topics, the dormant ones and known Other, at cosine 0.75 (as step b). A dormant topic comes back when its
 * traces reach the size that makes a new-topic candidate big: max(share, share of the last 7 days) at least
 * {@value NightlyUpdate#BIG_SHARE}. The largest comes back first, and none while the list is full.
 */
@UtilityClass
public class Revival {

    /** @return the indices of the topics revived tonight, largest first */
    public static List<Integer> apply(@NonNull NightState state, @NonNull NightWindow window, int maxTopics) {
        var dormant = state.dormant();
        if (dormant.isEmpty() || state.liveCount() >= maxTopics) {
            return List.of();
        }
        var vectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t : state.live()) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        for (int t : dormant) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        for (var prototype : state.getOtherPrototypes()) {
            vectors.add(prototype);
            owners.add(NightlyUpdate.KNOWN_OTHER);
        }
        var labels = new PrototypeAssigner(vectors, owners, List.of(), window.dimensions()).assign(window.vectors());
        var recent = window.recent();
        int recentTotal = 0;
        for (boolean r : recent) {
            recentTotal += r ? 1 : 0;
        }
        int n = window.size();
        var counts = new int[state.getTopics().size()];
        var recentCounts = new int[state.getTopics().size()];
        for (int i = 0; i < n; i++) {
            if (labels[i] >= 0) {
                counts[labels[i]]++;
                recentCounts[labels[i]] += recent[i] ? 1 : 0;
            }
        }
        int denominator = Math.max(1, recentTotal);
        var back = new ArrayList<Integer>();
        for (int t : dormant) {
            double share = Math.max((double) counts[t] / n, (double) recentCounts[t] / denominator);
            if (share >= NightlyUpdate.BIG_SHARE) {
                back.add(t);
            }
        }
        back.sort(Comparator.comparingInt((Integer t) -> -counts[t]).thenComparingInt(t -> t));
        var revived = new ArrayList<Integer>();
        for (int t : back) {
            if (state.liveCount() >= maxTopics) {
                break;
            }
            state.revive(t);
            revived.add(t);
        }
        return revived;
    }
}
