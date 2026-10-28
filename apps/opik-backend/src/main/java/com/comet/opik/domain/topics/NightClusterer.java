package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.Arrays;
import java.util.UUID;

/**
 * The k-means of a nightly update, behind an interface so the fixture test can inject the stand's results. A request
 * names what is clustered: a topic's members ({@link Kind#RECENTRE_TOPIC} with its id), the known-Other members, or the
 * unassigned traces. The labels are per unique text of {@code points}.
 */
public interface NightClusterer {

    KMeans.Result fit(@NonNull Request request);

    /** {@link KMeans}: a warm start (one Lloyd run) when {@code init} is given, else k-means++ with restarts. */
    NightClusterer KMEANS = request -> {
        var merged = request.points();
        var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
        return request.init() != null
                ? KMeans.fitFrom(merged.vectors(), weights, merged.dimensions(), request.init())
                : KMeans.fit(merged.vectors(), weights, merged.dimensions(), request.k(), request.seed());
    };

    enum Kind {
        RECENTRE_TOPIC,
        RECENTRE_OTHER,
        UNASSIGNED
    }

    /**
     * @param topic the topic recentred, for {@link Kind#RECENTRE_TOPIC}
     * @param init  the old prototypes ({@code k} rows) for a warm start, or null
     */
    @Builder
    record Request(@NonNull Kind kind, UUID topic, @NonNull IntentMerger.Merged points, int k, long seed,
            float[] init) {
    }
}
