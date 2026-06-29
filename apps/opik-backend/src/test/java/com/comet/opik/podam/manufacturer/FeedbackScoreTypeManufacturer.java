package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import com.comet.opik.api.ScoreSource;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * sourceQueueId must be null by default — only set explicitly for queue-scoped tests.
 * PODAM's UUIDTypeManufacturer can't distinguish field names reliably, so we manufacture
 * these types explicitly.
 */
public class FeedbackScoreTypeManufacturer {

    public static final AbstractTypeManufacturer<FeedbackScore> SCORE = new AbstractTypeManufacturer<>() {
        @Override
        public FeedbackScore getType(DataProviderStrategy strategy, AttributeMetadata metadata,
                ManufacturingContext context) {
            return FeedbackScore.builder()
                    .name(strategy.getTypeValue(metadata, context, String.class))
                    .categoryName(strategy.getTypeValue(metadata, context, String.class))
                    .value(strategy.getTypeValue(metadata, context, BigDecimal.class))
                    .reason(strategy.getTypeValue(metadata, context, String.class))
                    .source(ScoreSource.values()[Math.abs(
                            strategy.getTypeValue(metadata, context, Integer.class)) % ScoreSource.values().length])
                    .build();
        }
    };

    public static final AbstractTypeManufacturer<FeedbackScoreBatchItem> BATCH_ITEM = new AbstractTypeManufacturer<>() {
        @Override
        public FeedbackScoreBatchItem getType(DataProviderStrategy strategy, AttributeMetadata metadata,
                ManufacturingContext context) {
            return FeedbackScoreBatchItem.builder()
                    .id(strategy.getTypeValue(metadata, context, UUID.class))
                    .projectName(strategy.getTypeValue(metadata, context, String.class))
                    .name(strategy.getTypeValue(metadata, context, String.class))
                    .categoryName(strategy.getTypeValue(metadata, context, String.class))
                    .value(strategy.getTypeValue(metadata, context, BigDecimal.class))
                    .reason(strategy.getTypeValue(metadata, context, String.class))
                    .source(ScoreSource.values()[Math.abs(
                            strategy.getTypeValue(metadata, context, Integer.class)) % ScoreSource.values().length])
                    .build();
        }
    };

    public static final AbstractTypeManufacturer<FeedbackScoreBatchItemThread> BATCH_ITEM_THREAD = new AbstractTypeManufacturer<>() {
        @Override
        public FeedbackScoreBatchItemThread getType(DataProviderStrategy strategy, AttributeMetadata metadata,
                ManufacturingContext context) {
            return FeedbackScoreBatchItemThread.builder()
                    .threadId(strategy.getTypeValue(metadata, context, String.class))
                    .projectName(strategy.getTypeValue(metadata, context, String.class))
                    .name(strategy.getTypeValue(metadata, context, String.class))
                    .categoryName(strategy.getTypeValue(metadata, context, String.class))
                    .value(strategy.getTypeValue(metadata, context, BigDecimal.class))
                    .reason(strategy.getTypeValue(metadata, context, String.class))
                    .source(ScoreSource.values()[Math.abs(
                            strategy.getTypeValue(metadata, context, Integer.class)) % ScoreSource.values().length])
                    .build();
        }
    };
}
