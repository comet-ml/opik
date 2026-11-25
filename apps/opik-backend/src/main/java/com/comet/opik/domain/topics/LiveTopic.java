package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * A live topic as it stands tonight, from step a's labels: {@code topic} is its index in the night's topic list, the
 * shares are of the window and of its recent days, and {@code examples} are its member texts picked as a group's.
 */
@Builder(toBuilder = true)
record LiveTopic(int topic, @NonNull String name, @NonNull String definition, double share, double recentShare,
        @NonNull List<String> examples) {
}
