package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record KpiCardCriteria(
        @NonNull UUID projectId,
        List<? extends Filter> filters,
        @NonNull Instant intervalStart,
        @NonNull Instant intervalEnd) {
}
