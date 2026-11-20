package com.comet.opik.domain.topics;

import lombok.Builder;

/** A k-means group's size and its share of the window, from {@link GroupSummaries#summarize}. */
@Builder(toBuilder = true)
public record GroupSummary(int group, int size, double share) {
}
