package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Per-prompt classification result, sister to {@link ExperimentProjectMapping}. {@code projectId}
 * is any non-orphan {@code project_id} of an experiment that references this prompt via either
 * the legacy {@code prompt_id} column or the {@code prompt_versions} map; meaningful only when
 * {@code projectCount = 1}. {@code projectCount} is the distinct count of such non-orphan
 * project_ids — {@code 0} = no inference, {@code 1} = certain, {@code > 1} = ambiguous.
 */
@Builder(toBuilder = true)
public record PromptProjectClassification(@NonNull UUID promptId, UUID projectId, long projectCount) {
}
