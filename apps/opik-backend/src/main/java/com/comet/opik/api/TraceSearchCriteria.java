package com.comet.opik.api;

import com.comet.opik.api.filter.Filter;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record TraceSearchCriteria(
        String projectName,
        UUID projectId,
        List<? extends Filter> filters,
        boolean truncate) {
}
