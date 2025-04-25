package com.comet.opik.api;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.SpanType;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.Span.SpanField;

@Builder(toBuilder = true)
public record SpanSearchCriteria(
        String projectName,
        UUID projectId,
        UUID traceId,
        SpanType type,
        List<? extends Filter> filters,
        boolean truncate,
        UUID lastReceivedSpanId,
        List<SortingField> sortingFields,
        Set<SpanField> exclude) {
}
