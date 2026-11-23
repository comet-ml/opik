package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * A k-means group of the nightly update as its prompts show it: size, share of the window, share of the window's
 * recent days, tightness (mean cosine of its traces to the centre) and examples.
 */
@Builder(toBuilder = true)
record NightGroup(int group, int size, double share, double recentShare, double tightness,
        @NonNull List<String> examples) {
}
