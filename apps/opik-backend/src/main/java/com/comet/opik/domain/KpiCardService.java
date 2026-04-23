package com.comet.opik.domain;

import com.comet.opik.api.metrics.KpiCardResponse;
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

    @Override
    public Mono<KpiCardResponse> getKpiCards(@NonNull KpiCardCriteria criteria) {
        return switch (criteria.entityType()) {
            case TRACES -> kpiCardDAO.getTraceKpiCards(criteria);
            case SPANS -> kpiCardDAO.getSpanKpiCards(criteria);
            case THREADS -> kpiCardDAO.getThreadKpiCards(criteria);
        };
    }
}
