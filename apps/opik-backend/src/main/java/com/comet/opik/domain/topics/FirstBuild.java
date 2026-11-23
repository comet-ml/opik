package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * A first list. {@code labels[i]} is the index in {@code topics} of window trace i's topic, or
 * {@link TopicAlgorithm#OTHER}. A topic's saved vectors are the {@code topicPrototypes} it owns by {@code owners}, in
 * order; {@code otherPrototypes} are the known-Other ones.
 */
@Builder(toBuilder = true)
public record FirstBuild(@NonNull List<NewTopic> topics, @NonNull int[] labels, @NonNull List<float[]> topicPrototypes,
        @NonNull List<Integer> owners, @NonNull List<float[]> otherPrototypes) {
}
