package com.comet.opik.podam.manufacturer.anthropic;

import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.List;

public class AnthropicCreateMessageResponseManufacturer
        extends
            AbstractTypeManufacturer<AnthropicCreateMessageResponse> {
    public static final AnthropicCreateMessageResponseManufacturer INSTANCE = new AnthropicCreateMessageResponseManufacturer();

    @Override
    public AnthropicCreateMessageResponse getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        var response = new AnthropicCreateMessageResponse();
        response.id = strategy.getTypeValue(metadata, context, String.class);
        response.model = strategy.getTypeValue(metadata, context, String.class);
        response.stopReason = strategy.getTypeValue(metadata, context, String.class);
        response.content = List.of(strategy.getTypeValue(metadata, context, AnthropicContent.class));
        response.usage = strategy.getTypeValue(metadata, context, AnthropicUsage.class);

        return response;
    }
}
