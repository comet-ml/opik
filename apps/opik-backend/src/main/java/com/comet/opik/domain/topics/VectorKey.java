package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import lombok.NonNull;

import java.util.UUID;

/** A {@code topic_vectors} row's key within a workspace's read. */
record VectorKey(@NonNull UUID projectId, @NonNull UUID traceId, int taskVersion) {

    static VectorKey of(TopicVector vector) {
        return new VectorKey(vector.projectId(), vector.traceId(), vector.taskVersion());
    }

    static VectorKey of(Candidate candidate) {
        return new VectorKey(candidate.projectId(), candidate.traceId(), candidate.taskVersion());
    }
}
