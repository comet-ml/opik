package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.NonNull;

/**
 * A nightly update's labels, per window trace an index in the state's topics or {@link TopicAlgorithm#OTHER}:
 * {@code before} by the list as saved before tonight, {@code labels} by the list after tonight's changes. The service
 * computes the churn from the two. {@code stats} are the algorithm's stats of the night.
 */
@Builder(toBuilder = true)
public record TopicUpdate(@NonNull int[] before, @NonNull int[] labels, @NonNull ObjectNode stats) {
}
