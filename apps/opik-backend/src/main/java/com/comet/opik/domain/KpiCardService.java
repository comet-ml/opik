package com.comet.opik.domain;

import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.domain.topics.TopicReadService;
import com.comet.opik.domain.topics.TopicsReadErrors;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@ImplementedBy(KpiCardServiceImpl.class)
public interface KpiCardService {

    Mono<KpiCardResponse> getKpiCards(KpiCardCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class KpiCardServiceImpl implements KpiCardService {

    private final @NonNull KpiCardDAO kpiCardDAO;
    private final @NonNull TopicReadService topicReadService;

    @Override
    public Mono<KpiCardResponse> getKpiCards(@NonNull KpiCardCriteria criteria) {
        return switch (criteria.entityType()) {
            case TRACES -> topicReadService.filterScope(criteria.filters(), criteria.projectId())
                    .map(topicScope -> criteria.toBuilder().topicScope(topicScope.orElse(null)).build())
                    .flatMap(scoped -> TopicsReadErrors.busyOnFilterGuard(scoped.topicScope() != null,
                            kpiCardDAO.getTraceKpiCards(scoped)));
            case SPANS -> kpiCardDAO.getSpanKpiCards(criteria);
            case THREADS -> kpiCardDAO.getThreadKpiCards(criteria);
        };
    }
}
