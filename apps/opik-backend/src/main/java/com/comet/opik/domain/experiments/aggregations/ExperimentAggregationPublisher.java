package com.comet.opik.domain.experiments.aggregations;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.UUID;

@ImplementedBy(ExperimentAggregationPublisher.ExperimentAggregationPublisherImpl.class)
public interface ExperimentAggregationPublisher {

    void publish(@NonNull Set<UUID> experimentIds, @NonNull String workspaceId, @NonNull String userName);

    @Singleton
    @Slf4j
    class ExperimentAggregationPublisherImpl implements ExperimentAggregationPublisher {

        @Inject
        ExperimentAggregationPublisherImpl() {
        }

        @Override
        public void publish(@NonNull Set<UUID> experimentIds, @NonNull String workspaceId,
                @NonNull String userName) {
            // TODO: implement debounce mechanism before enqueuing to Redis stream
            log.debug("Experiment aggregation publish skipped for experiments '{}': debounce mechanism not yet implemented",
                    experimentIds);
        }
    }
}
