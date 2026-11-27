package com.comet.opik.domain.topics;

import java.util.List;
import java.util.UUID;

/** Labels vectors by one saved list. Local and fast: no network call. */
@FunctionalInterface
public interface TopicLabeller {

    /**
     * The topic of each vector: a topic's id, {@link TraceTopic#OTHER}, or null for a vector that is not
     * {@link TopicEmbedding#usable}, which gets no label.
     */
    UUID[] label(List<float[]> vectors);
}
