package com.comet.opik.api;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record AnnotationQueueSearchCriteria(
        String name,
        List<? extends Filter> filters,
        List<SortingField> sortingFields) {
}
