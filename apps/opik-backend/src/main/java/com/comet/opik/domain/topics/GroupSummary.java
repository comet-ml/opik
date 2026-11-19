package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * A k-means group as a prompt shows it: its size and share of the window, from {@link GroupSummaries#summarize}. The
 * nightly prompts also read its recent share, tightness and examples; the author prompt leaves them unset.
 */
@Builder(toBuilder = true)
public record GroupSummary(int group, int size, double share, double recentShare, double tightness,
        @NonNull List<String> examples) {
}
