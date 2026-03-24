package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

@ImplementedBy(KpiCardServiceImpl.class)
public interface KpiCardService {

    Mono<KpiCardResponse> getKpiCards(@NonNull UUID projectId, @NonNull KpiCardRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class KpiCardServiceImpl implements KpiCardService {

    private final @NonNull KpiCardDAO kpiCardDAO;
    private final @NonNull FiltersFactory filtersFactory;

    @Override
    public Mono<KpiCardResponse> getKpiCards(@NonNull UUID projectId, @NonNull KpiCardRequest request) {
        var filters = parseFilters(request);

        var criteria = KpiCardCriteria.builder()
                .projectId(projectId)
                .filters(filters)
                .intervalStart(request.intervalStart())
                .intervalEnd(request.intervalEnd())
                .build();

        return switch (request.entityType()) {
            case TRACES -> kpiCardDAO.getTraceKpiCards(criteria);
            case SPANS -> kpiCardDAO.getSpanKpiCards(criteria);
            case THREADS -> kpiCardDAO.getThreadKpiCards(criteria);
        };
    }

    private java.util.List<? extends Filter> parseFilters(KpiCardRequest request) {
        return switch (request.entityType()) {
            case TRACES -> filtersFactory.newFilters(request.filters(), TraceFilter.LIST_TYPE_REFERENCE);
            case SPANS -> filtersFactory.newFilters(request.filters(), SpanFilter.LIST_TYPE_REFERENCE);
            case THREADS -> filtersFactory.newFilters(request.filters(), TraceThreadFilter.LIST_TYPE_REFERENCE);
        };
    }
}
