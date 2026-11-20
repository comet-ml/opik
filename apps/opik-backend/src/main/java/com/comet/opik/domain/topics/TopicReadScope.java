package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * The MySQL half of a read of a project's topics: its {@code topic_projects} row, null before the project's first
 * attempt, and its topics, empty before its first saved run.
 */
@Builder(toBuilder = true)
public record TopicReadScope(@NonNull List<Topic> live, @Nullable TopicProject project) {
}
