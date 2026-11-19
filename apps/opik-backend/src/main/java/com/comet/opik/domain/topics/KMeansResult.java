package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

/**
 * A {@link KMeans} fit: per point its cluster, the {@code k * dimensions} unit-length float32 centres, the weighted
 * inertia and the iterations the kept restart took.
 */
@Builder(toBuilder = true)
public record KMeansResult(@NonNull int[] labels, @NonNull float[] centres, double inertia, int iterations) {
}
