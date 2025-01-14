package com.comet.opik.podam.manufacturer.anthropic;

import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

public class AnthropicContentManufacturer extends AbstractTypeManufacturer<AnthropicContent> {
    public static final AnthropicContentManufacturer INSTANCE = new AnthropicContentManufacturer();

    @Override
    public AnthropicContent getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        var content = new AnthropicContent();
        content.name = strategy.getTypeValue(metadata, context, String.class);
        content.text = strategy.getTypeValue(metadata, context, String.class);
        content.id = strategy.getTypeValue(metadata, context, String.class);

        return content;
    }
}
