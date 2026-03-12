package com.comet.opik.domain;

import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import io.r2dbc.spi.Statement;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Utility class for binding ExperimentSearchCriteria parameters to R2DBC statements.
 * Centralizes the binding logic to avoid duplication between ExperimentDAO and ExperimentAggregatesDAO.
 */
public final class ExperimentSearchCriteriaBinder {

    private ExperimentSearchCriteriaBinder() {
        // Utility class - prevent instantiation
    }

    /**
     * Binds search criteria parameters to a statement.
     *
     * @param statement the R2DBC statement to bind parameters to
     * @param criteria the search criteria containing filter values
     * @param filterQueryBuilder the filter query builder for binding filters
     * @param filterStrategies the list of filter strategies to apply
     * @param bindEntityType whether to bind the entity_type parameter
     */
    public static void bindSearchCriteria(
            Statement statement,
            ExperimentSearchCriteria criteria,
            FilterQueryBuilder filterQueryBuilder,
            List<FilterStrategy> filterStrategies,
            boolean bindEntityType) {

        // Bind basic criteria
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> statement.bind("name", name));
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds.toArray(UUID[]::new)));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> statement.bind("prompt_ids", List.of(promptId).toArray(UUID[]::new)));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId));
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> statement.bind("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> statement.bind("types", types));
        Optional.ofNullable(criteria.experimentIds())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(experimentIds -> statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new)));

        // Bind filter strategies
        Optional.ofNullable(criteria.filters())
                .ifPresent(filters -> {
                    for (FilterStrategy strategy : filterStrategies) {
                        filterQueryBuilder.bind(statement, filters, strategy);
                    }
                });

        // Optionally bind entity type
        if (bindEntityType) {
            statement.bind("entity_type", criteria.entityType().getType());
        }
    }
}
