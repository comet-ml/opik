package com.comet.opik.domain.topics;

import com.comet.opik.api.events.RedisSubscriberMessage;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One queued run on the {@code topics.runs} stream. Delivery only: the project's {@code topic_projects} row says whether
 * the run is still wanted, so a duplicate or a late message finds the row past {@code queued} and does nothing.
 *
 * @param night the UTC date whose start ends a build's window; null for a labelling pass
 * @param windowTraces the window's traces with a vector, as the planner counted them; 0 for a labelling pass
 */
@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TopicsRunMessage(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
        @NonNull TopicProject.RunMode mode, @Nullable LocalDate night, long windowTraces)
        implements
            RedisSubscriberMessage {
}
