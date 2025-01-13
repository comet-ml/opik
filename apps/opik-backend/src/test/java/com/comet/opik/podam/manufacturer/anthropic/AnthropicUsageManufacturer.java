package com.comet.opik.podam.manufacturer.anthropic;

import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

public class AnthropicUsageManufacturer extends AbstractTypeManufacturer<AnthropicUsage> {
    public static final AnthropicUsageManufacturer INSTANCE = new AnthropicUsageManufacturer();

    @Override
    public AnthropicUsage getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        var usage = new AnthropicUsage();
        usage.inputTokens = strategy.getTypeValue(metadata, context, Integer.class);
        usage.outputTokens = strategy.getTypeValue(metadata, context, Integer.class);

        return usage;
    }
}
