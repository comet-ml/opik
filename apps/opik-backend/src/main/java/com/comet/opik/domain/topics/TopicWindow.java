package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

/**
 * The window a run reads: per trace, in time order, its intent text and its vector of
 * {@value TopicEmbedding#DIMENSIONS} values ({@code vectors} holds them one after another).
 */
@Builder(toBuilder = true)
public record TopicWindow(@NonNull String[] texts, @NonNull float[] vectors) {

    public int size() {
        return texts.length;
    }
}
