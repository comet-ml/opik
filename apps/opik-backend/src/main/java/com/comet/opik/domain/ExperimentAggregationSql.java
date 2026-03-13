package com.comet.opik.domain;

import lombok.Builder;
import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.UUID;

@UtilityClass
public class ExperimentAggregationSql {

    static final String SELECT_AGGREGATED_EXPERIMENT_IDS = """
            SELECT
                count() AS total,
                countIf(has_aggregated) AS aggregated,
                countIf(NOT has_aggregated) AS not_aggregated
            FROM (
                SELECT
                    e.id,
                    notEmpty(agg.id) AS has_aggregated
                FROM experiments e FINAL
                LEFT JOIN (
                    SELECT
                        toString(id) AS id
                    FROM experiment_aggregates
                    WHERE workspace_id = :workspace_id
                    <if(experiment_ids)> AND id IN :experiment_ids <endif>
                    <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                    <if(id)> AND id = :id <endif>
                    <if(ids_list)> AND id IN :ids_list <endif>
                ) agg ON e.id = agg.id
                WHERE e.workspace_id = :workspace_id
                <if(experiment_ids)> AND e.id IN :experiment_ids <endif>
                <if(dataset_id)> AND e.dataset_id = :dataset_id <endif>
                <if(id)> AND e.id = :id <endif>
                <if(ids_list)> AND e.id IN :ids_list <endif>
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    @Builder(toBuilder = true)
    public record AggregationBranchCountsCriteria(
            Set<UUID> experimentIds,
            UUID datasetId,
            UUID id,
            Set<UUID> idsList) {

        static AggregationBranchCountsCriteria empty() {
            return AggregationBranchCountsCriteria.builder().build();
        }
    }

    public record AggregatedExperimentCounts(long aggregated, long notAggregated) {
        public static final AggregatedExperimentCounts BOTH_BRANCHES = new AggregatedExperimentCounts(1, 1);

        public boolean hasAggregated() {
            return aggregated == 0 && notAggregated == 0 || aggregated > 0;
        }

        public boolean hasRaw() {
            return aggregated == 0 && notAggregated == 0 || notAggregated > 0;
        }
    }
}
