package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record TraceSearchCriteria(
        String projectName,
        UUID projectId,
        List<? extends Filter> filters,
        List<SortingField> sortingFields,
        UUID lastReceivedId,
        boolean truncate,
        boolean stripAttachments,
        Set<Trace.TraceField> exclude,
        UUID uuidFromTime,
        UUID uuidToTime) {
}
