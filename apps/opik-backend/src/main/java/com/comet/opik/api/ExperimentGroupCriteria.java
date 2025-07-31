package com.comet.opik.api;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.grouping.GroupBy;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Set;

@Builder(toBuilder = true)
public record ExperimentGroupCriteria(
        @NonNull List<GroupBy> groups,
        String name,
        Set<ExperimentType> types,
        List<? extends Filter> filters) {
}
