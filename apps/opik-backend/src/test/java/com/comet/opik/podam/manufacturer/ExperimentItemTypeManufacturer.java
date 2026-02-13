package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.VisibilityMode;
import com.fasterxml.jackson.databind.JsonNode;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ExperimentItemTypeManufacturer extends AbstractTypeManufacturer<ExperimentItem> {

    public static final ExperimentItemTypeManufacturer INSTANCE = new ExperimentItemTypeManufacturer();

    @Override
    public ExperimentItem getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        var now = Instant.now();

        return ExperimentItem.builder()
                .id(strategy.getTypeValue(metadata, context, UUID.class))
                .experimentId(strategy.getTypeValue(metadata, context, UUID.class))
                .datasetItemId(strategy.getTypeValue(metadata, context, UUID.class))
                .traceId(strategy.getTypeValue(metadata, context, UUID.class))
                .projectId(null) // Read-only field, always null from API
                .projectName(strategy.getTypeValue(metadata, context, String.class)) // Write-only field for tests
                .input(strategy.getTypeValue(metadata, context, JsonNode.class))
                .output(strategy.getTypeValue(metadata, context, JsonNode.class))
                .feedbackScores(strategy.getTypeValue(metadata, context, List.class))
                .comments(strategy.getTypeValue(metadata, context, List.class))
                .totalEstimatedCost(strategy.getTypeValue(metadata, context, BigDecimal.class))
                .duration(strategy.getTypeValue(metadata, context, Double.class))
                .usage(strategy.getTypeValue(metadata, context, Map.class))
                .createdAt(now)
                .lastUpdatedAt(now)
                .createdBy(strategy.getTypeValue(metadata, context, String.class))
                .lastUpdatedBy(strategy.getTypeValue(metadata, context, String.class))
                .traceVisibilityMode(strategy.getTypeValue(metadata, context, VisibilityMode.class))
                .build();
    }
}
