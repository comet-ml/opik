package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The MySQL half of every read of a project's topics, by the topics read API and by the traces filter by topic: which
 * {@code trace_topics} rows count, and as which topic. Only arrival rows and rows of {@code savedRunIds} count; a
 * trace's latest such row gives its topic, and a topic that is not in {@code live} counts as Other.
 *
 * <p>{@link #toString()} prints counts only: a trace search logs its criteria, and topic names are customer text.
 */
@Builder(toBuilder = true)
public record TopicReadScope(@NonNull List<Topic> live, @NonNull List<UUID> savedRunIds) {

    /**
     * Empty when the workspace is not eligible for topics. The saved runs are read only when the project has a live
     * topic: without one, no row is read.
     */
    static Optional<TopicReadScope> read(@NonNull Handle handle, @NonNull String workspaceId,
            @NonNull UUID projectId) {
        if (!handle.attach(TopicWorkspaceDAO.class).isEligible(workspaceId)) {
            return Optional.empty();
        }
        var live = handle.attach(TopicDAO.class).findLive(workspaceId, projectId);
        var savedRunIds = live.isEmpty()
                ? List.<UUID>of()
                : handle.attach(TopicRunDAO.class).findSavedRunIds(workspaceId, projectId);
        return Optional.of(TopicReadScope.builder().live(live).savedRunIds(savedRunIds).build());
    }

    public List<String> liveIds() {
        return live.stream().map(topic -> topic.id().toString()).toList();
    }

    public List<String> savedRunIdStrings() {
        return savedRunIds.stream().map(UUID::toString).toList();
    }

    @Override
    public String toString() {
        return "TopicReadScope[live=%d, savedRunIds=%d]".formatted(live.size(), savedRunIds.size());
    }
}
