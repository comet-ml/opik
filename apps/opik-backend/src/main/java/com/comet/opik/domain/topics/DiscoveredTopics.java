package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * A first list. {@code labels[i]} is the index in {@code topics} of window trace i's topic, or
 * {@link TopicAlgorithm#OTHER}; {@code otherPrototypes} are the known-Other ones. {@code stats} are the algorithm's
 * stats of this step.
 */
@Builder(toBuilder = true)
public record DiscoveredTopics(@NonNull List<NewTopic> topics, @NonNull int[] labels,
        @NonNull List<float[]> otherPrototypes,
        @NonNull ObjectNode stats) {

    /** A list's prototypes are unit vectors: its topics' check them, and this checks the known-Other ones. */
    public DiscoveredTopics {
        for (int p = 0; p < otherPrototypes.size(); p++) {
            PrototypeVectors.checkUnit(p, otherPrototypes.get(p));
        }
    }
}
