package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.metrics.KpiCardRequest.EntityType;
import com.comet.opik.domain.topics.TopicFilterScope;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record KpiCardCriteria(
        @NonNull UUID projectId,
        @NonNull EntityType entityType,
        List<? extends Filter> filters,
        @NonNull Instant intervalStart,
        Instant intervalEnd,
        /** Set by {@code KpiCardService} for trace cards with a {@code topic_id} filter and a scope; else null. */
        TopicFilterScope topicScope) {
}
