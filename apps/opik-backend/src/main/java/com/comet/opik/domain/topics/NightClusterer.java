package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.Arrays;

/**
 * The k-means of a nightly update, behind an interface so a test can watch or replace it: the Other traces are
 * grouped once a night ({@link CandidateGrouper}). The labels are per unique text of {@code points}.
 */
public interface NightClusterer {

    KMeans.Result fit(@NonNull Request request);

    /** {@link KMeans}: k-means++ with restarts, seeded. */
    NightClusterer KMEANS = request -> {
        var merged = request.points();
        var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
        return KMeans.fit(merged.vectors(), weights, merged.dimensions(), request.k(), request.seed());
    };

    @Builder
    record Request(@NonNull IntentMerger.Merged points, int k, long seed) {
    }
}
