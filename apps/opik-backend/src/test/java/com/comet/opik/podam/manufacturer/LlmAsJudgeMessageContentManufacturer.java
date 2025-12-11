package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.random.RandomGenerator;

/**
 * Custom PODAM manufacturer for LlmAsJudgeMessageContent that generates valid content
 * based on the content type (text, image_url, video_url, or audio_url).
 */
public class LlmAsJudgeMessageContentManufacturer extends AbstractTypeManufacturer<LlmAsJudgeMessageContent> {

    public static final LlmAsJudgeMessageContentManufacturer INSTANCE = new LlmAsJudgeMessageContentManufacturer();
    private static final RandomGenerator random = RandomGenerator.getDefault();

    @Override
    public LlmAsJudgeMessageContent getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        int typeChoice = random.nextInt(4);

        return switch (typeChoice) {
            case 0 -> // text content
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text(strategy.getTypeValue(metadata, context, String.class))
                        .build();
            case 1 -> // image_url content
                LlmAsJudgeMessageContent.builder()
                        .type("image_url")
                        .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                                .url(strategy.getTypeValue(metadata, context, String.class))
                                .detail("auto")
                                .build())
                        .build();
            case 2 -> // video_url content
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url(strategy.getTypeValue(metadata, context, String.class))
                                .build())
                        .build();
            default -> // audio_url content
                LlmAsJudgeMessageContent.builder()
                        .type("audio_url")
                        .audioUrl(LlmAsJudgeMessageContent.AudioUrl.builder()
                                .url(strategy.getTypeValue(metadata, context, String.class))
                                .build())
                        .build();
        };
    }
}
