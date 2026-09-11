package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import dev.langchain4j.data.message.ChatMessageType;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

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

        String content = strategy.getTypeValue(metadata, context, String.class);

        return LlmAsJudgeMessage.builder()
                .role(role)
                .content(content)
                .build();
    }
}
