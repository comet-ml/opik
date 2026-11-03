package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * A job's status and one page of its results. {@code results} is empty when polled with {@code results=false}, and
 * {@code next_cursor} is null on the last page. The service's cursor can repeat items across pages.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobView(@NonNull String jobId, @NonNull InferenceEndpoint endpoint, @NonNull JobStatus status,
        @NonNull JobCounts counts, boolean cancelRequested, @NonNull List<ItemResult> results, Integer nextCursor) {
}
