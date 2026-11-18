package com.comet.opik.domain;

import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.topics.TopicFilterScope;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record TraceSearchCriteria(
        String projectName,
        UUID projectId,
        Source source,
        List<? extends Filter> filters,
        List<SortingField> sortingFields,
        UUID lastReceivedId,
        boolean truncate,
        boolean stripAttachments,
        Set<Trace.TraceField> exclude,
        Set<TraceThread.TraceThreadField> excludeThreadFields,
        UUID uuidFromTime,
        UUID uuidToTime,
        String searchText,
        UUID annotationQueueId,
        /** Set by {@code TraceService} for a search with a {@code topic_id} filter and a scope; else null. */
        @Nullable TopicFilterScope topicScope) {
}
