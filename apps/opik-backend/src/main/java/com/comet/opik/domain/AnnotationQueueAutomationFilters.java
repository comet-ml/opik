package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.annotationqueue.AutomationFilter;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Turns an automation's filter groups into the trace or thread filters the traces and threads queries take.
 *
 * <p>Filters go through {@link FiltersFactory} exactly as the traces and threads endpoints' filters do, so an
 * automation selects what the same filters select on the traces or threads page.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AnnotationQueueAutomationFilters {

    private static final TypeReference<List<List<AutomationFilter>>> FILTER_GROUPS = new TypeReference<>() {
    };

    private final @NonNull FiltersFactory filtersFactory;

    /**
     * Parses and validates every group, rejecting what the traces or threads endpoint would reject: a field
     * that does not exist for the scope, or an operator the field does not take, is a 400.
     */
    public List<List<Filter>> toFilterGroups(@NonNull AnnotationQueue.AnnotationScope scope,
            List<List<AutomationFilter>> groups) {
        if (CollectionUtils.isEmpty(groups)) {
            return List.of();
        }

        return groups.stream()
                .map(group -> toFilters(scope, group))
                .filter(CollectionUtils::isNotEmpty)
                .toList();
    }

    public List<List<Filter>> toFilterGroups(@NonNull AnnotationQueue.AnnotationScope scope,
            String filterGroupsJson) {
        return toFilterGroups(scope, readFilterGroups(filterGroupsJson));
    }

    public static List<List<AutomationFilter>> readFilterGroups(String filterGroupsJson) {
        return StringUtils.isBlank(filterGroupsJson)
                ? null
                : JsonUtils.readValue(filterGroupsJson, FILTER_GROUPS);
    }

    private List<Filter> toFilters(AnnotationQueue.AnnotationScope scope, List<AutomationFilter> group) {
        if (CollectionUtils.isEmpty(group)) {
            return List.of();
        }

        String json = JsonUtils.writeValueAsString(group);
        List<? extends Filter> parsed = switch (scope) {
            case TRACE -> filtersFactory.newFilters(json, TraceFilter.LIST_TYPE_REFERENCE);
            case THREAD -> filtersFactory.newFilters(json, TraceThreadFilter.LIST_TYPE_REFERENCE);
        };
        return parsed == null ? List.of() : List.copyOf(parsed);
    }
}
