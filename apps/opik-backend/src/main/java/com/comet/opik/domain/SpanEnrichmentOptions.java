package com.comet.opik.domain;

import lombok.Builder;

/**
 * Options for span enrichment specifying which metadata to include.
 */
@Builder(toBuilder = true)
public record SpanEnrichmentOptions(
        boolean includeTags,
        boolean includeFeedbackScores,
        boolean includeComments,
        boolean includeUsage,
        boolean includeMetadata) {
}
