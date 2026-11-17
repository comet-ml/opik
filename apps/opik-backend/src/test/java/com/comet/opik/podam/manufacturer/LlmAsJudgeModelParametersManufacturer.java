package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.fasterxml.jackson.databind.JsonNode;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

/**
 * Leaves {@code api} unset: random rules have random model names, which no Decisions API serves, so a rule asking for
 * one is rejected on save.
 */
public class LlmAsJudgeModelParametersManufacturer extends AbstractTypeManufacturer<LlmAsJudgeModelParameters> {

    public static final LlmAsJudgeModelParametersManufacturer INSTANCE = new LlmAsJudgeModelParametersManufacturer();

    @Override
    public LlmAsJudgeModelParameters getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        return LlmAsJudgeModelParameters.builder()
                .name(strategy.getTypeValue(metadata, context, String.class))
                .temperature(strategy.getTypeValue(metadata, context, Double.class))
                .seed(strategy.getTypeValue(metadata, context, Integer.class))
                .customParameters(strategy.getTypeValue(metadata, context, JsonNode.class))
                .build();
    }
}
