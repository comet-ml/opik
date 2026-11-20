package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

/**
 * A nightly update's labels, per window trace an index in the state's topics or {@link TopicAlgorithm#OTHER}:
 * {@code before} by the list as saved before tonight, {@code labels} by the list after tonight's changes. The service
 * computes the churn from the two.
 */
@Builder(toBuilder = true)
public record TopicUpdate(@NonNull int[] before, @NonNull int[] labels) {
}
