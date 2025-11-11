package com.comet.opik.domain;

import lombok.Builder;

/**
 * Options for trace enrichment specifying which metadata to include.
 */
@Builder(toBuilder = true)
public record TraceEnrichmentOptions(
        boolean includeSpans,
        boolean includeTags,
        boolean includeFeedbackScores,
        boolean includeComments,
        boolean includeUsage,
        boolean includeMetadata) {
}
