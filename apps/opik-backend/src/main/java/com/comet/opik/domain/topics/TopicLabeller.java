package com.comet.opik.domain.topics;

import java.util.UUID;

/** Labels vectors by one saved list. Local and fast: no network call. */
@FunctionalInterface
public interface TopicLabeller {

    /**
     * The topic of each vector: a topic's id, or {@link TraceTopic#OTHER}.
     *
     * @param vectors {@value TopicEmbedding#DIMENSIONS} values per vector, one after another
     */
    UUID[] label(float[] vectors);
}
