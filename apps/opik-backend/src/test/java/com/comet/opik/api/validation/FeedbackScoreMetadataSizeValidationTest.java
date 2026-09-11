package com.comet.opik.api.validation;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.MAX_FEEDBACK_SCORE_METADATA_SIZE_IN_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Feedback score metadata size validation")
class FeedbackScoreMetadataSizeValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static FeedbackScoreItem.FeedbackScoreBatchItem itemWithMetadata(Map<String, Object> metadata) {
        return new FeedbackScoreItem.FeedbackScoreBatchItem(
                "project", UUID.randomUUID(), "score-name", null,
                BigDecimal.ONE, null, ScoreSource.SDK, null, null, metadata, UUID.randomUUID());
    }

    private static Map<String, Object> metadataOfApproximateSize(int targetBytes) {
        var metadata = new HashMap<String, Object>();
        metadata.put("payload", "x".repeat(targetBytes));
        return metadata;
    }

    @Test
    @DisplayName("null metadata is valid")
    void nullMetadataIsValid() {
        Set<ConstraintViolation<FeedbackScoreItem.FeedbackScoreBatchItem>> violations = validator
                .validate(itemWithMetadata(null));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("metadata within the size cap is valid")
    void metadataWithinCapIsValid() {
        // leave room for the JSON envelope around the payload string
        var metadata = metadataOfApproximateSize((int) MAX_FEEDBACK_SCORE_METADATA_SIZE_IN_BYTES - 100);

        Set<ConstraintViolation<FeedbackScoreItem.FeedbackScoreBatchItem>> violations = validator
                .validate(itemWithMetadata(metadata));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("metadata over the size cap is rejected")
    void metadataOverCapIsRejected() {
        var metadata = metadataOfApproximateSize((int) MAX_FEEDBACK_SCORE_METADATA_SIZE_IN_BYTES + 1);

        Set<ConstraintViolation<FeedbackScoreItem.FeedbackScoreBatchItem>> violations = validator
                .validate(itemWithMetadata(metadata));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("metadata");
        assertThat(violations)
                .anyMatch(violation -> violation.getMessage()
                        .equals("exceeds the maximum allowed size of %d bytes"
                                .formatted(MAX_FEEDBACK_SCORE_METADATA_SIZE_IN_BYTES)));
    }

    @Test
    @DisplayName("thread batch item metadata is bounded by the same cap")
    void threadItemMetadataIsBounded() {
        var metadata = metadataOfApproximateSize((int) MAX_FEEDBACK_SCORE_METADATA_SIZE_IN_BYTES + 1);
        var item = new FeedbackScoreItem.FeedbackScoreBatchItemThread(
                "project", UUID.randomUUID(), "score-name", null,
                BigDecimal.ONE, null, ScoreSource.SDK, null, null, metadata, "thread-id");

        Set<ConstraintViolation<FeedbackScoreItem.FeedbackScoreBatchItemThread>> violations = validator.validate(item);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("metadata");
    }
}
