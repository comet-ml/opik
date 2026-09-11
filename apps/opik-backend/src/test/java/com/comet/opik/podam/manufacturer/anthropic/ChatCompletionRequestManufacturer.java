package com.comet.opik.podam.manufacturer.anthropic;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

public class ChatCompletionRequestManufacturer extends AbstractTypeManufacturer<ChatCompletionRequest> {
    public static final ChatCompletionRequestManufacturer INSTANCE = new ChatCompletionRequestManufacturer();

    @Override
    public ChatCompletionRequest getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        var userMessageContent = strategy.getTypeValue(metadata, context, String.class);
        var assistantMessageContent = strategy.getTypeValue(metadata, context, String.class);
        var systemMessageContent = strategy.getTypeValue(metadata, context, String.class);

        return ChatCompletionRequest.builder()
                .model(strategy.getTypeValue(metadata, context, String.class))
                .stream(strategy.getTypeValue(metadata, context, Boolean.class))
                .temperature(strategy.getTypeValue(metadata, context, Double.class))
                .topP(strategy.getTypeValue(metadata, context, Double.class))
                .stop(strategy.getTypeValue(metadata, context, String.class))
                .addUserMessage(userMessageContent)
                .addAssistantMessage(assistantMessageContent)
                .addSystemMessage(systemMessageContent)
                .maxCompletionTokens(strategy.getTypeValue(metadata, context, Integer.class))
                .build();
    }
}
