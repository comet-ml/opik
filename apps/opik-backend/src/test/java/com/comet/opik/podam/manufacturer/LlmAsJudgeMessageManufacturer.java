package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import dev.langchain4j.data.message.ChatMessageType;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Custom PODAM manufacturer for LlmAsJudgeMessage that generates valid content
 * (either String or List<LlmAsJudgeMessageContent>).
 */
public class LlmAsJudgeMessageManufacturer extends AbstractTypeManufacturer<LlmAsJudgeMessage> {

    public static final LlmAsJudgeMessageManufacturer INSTANCE = new LlmAsJudgeMessageManufacturer();
    private static final RandomGenerator random = RandomGenerator.getDefault();

    @Override
    public LlmAsJudgeMessage getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        var role = ChatMessageType.values()[random.nextInt(ChatMessageType.values().length)];

        // 50% chance of String content, 50% chance of List content
        Object content;
        if (random.nextBoolean()) {
            // Generate simple string content
            content = strategy.getTypeValue(metadata, context, String.class);
        } else {
            // Generate structured content (list of content parts)
            var contentPart1 = strategy.getTypeValue(metadata, context, LlmAsJudgeMessageContent.class);
            var contentPart2 = strategy.getTypeValue(metadata, context, LlmAsJudgeMessageContent.class);
            content = List.of(contentPart1, contentPart2);
        }

        return LlmAsJudgeMessage.builder()
                .role(role)
                .content(content)
                .build();
    }
}
