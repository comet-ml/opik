package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import lombok.NonNull;

/**
 * One row of {@code topic_vector_status}: a trace that can never get a vector, and why ({@link SkipReason#EMBED_REFUSED}
 * or {@link SkipReason#INVALID_VECTOR}). The window read leaves its key out.
 */
record TopicVectorStatus(@NonNull Candidate candidate, @NonNull SkipReason reason) {
}
