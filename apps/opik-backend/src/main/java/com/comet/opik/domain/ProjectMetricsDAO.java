package com.comet.opik.domain;

import com.comet.opik.api.AggregationType;
import com.comet.opik.api.DataPoint;
import com.comet.opik.api.TimeInterval;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ImplementedBy(ProjectMetricsDAOImpl.class)
public interface ProjectMetricsDAO {
    @Builder(toBuilder = true)
    record MetricsCriteria(@NonNull TimeInterval interval,
                                  Instant startTimestamp,
                                  Instant endTimestamp,
                                  @NonNull AggregationType aggregation) {}

    Mono<List<DataPoint<Integer>>> getTraceCount(UUID projectId, MetricsCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {
    @Override
    public Mono<List<DataPoint<Integer>>> getTraceCount(UUID projectId, MetricsCriteria criteria) {
        return Mono.just(List.of());
    }
}
