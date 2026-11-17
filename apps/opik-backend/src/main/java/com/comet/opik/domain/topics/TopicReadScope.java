package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * The MySQL half of a read of a project's topics: its state, null before its first saved run, and its live topics,
 * empty with no state.
 */
@Builder
public record TopicReadScope(@NonNull List<Topic> live, @Nullable TopicProjectState state) {
}
