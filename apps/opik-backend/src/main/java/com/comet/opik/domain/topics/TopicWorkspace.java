package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

/**
 * One row of {@code topic_workspaces}.
 *
 * @param vectorsCheckedAt the last time the topic vectors tick discovered the workspace's projects, about once per
 *        {@code topics.vectors.longWindowInterval}. Older than twice that: the tick has stopped for the workspace, and
 *        the projects' {@code backfillDrainedAt} says nothing about the traces since
 */
@Builder(toBuilder = true)
public record TopicWorkspace(@NonNull String workspaceId, @NonNull Instant vectorsCheckedAt) {
}
