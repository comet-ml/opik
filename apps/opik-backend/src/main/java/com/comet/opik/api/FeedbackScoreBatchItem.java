package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.MAX_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.MIN_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeedbackScoreBatchItem(
        // entity (trace or span) id
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonView( {
                FeedbackScoreBatch.View.Tracing.class}) @NotNull(groups = {
                        FeedbackScoreBatch.View.Tracing.class}) UUID id,
        // thread id
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonView({
                FeedbackScoreBatch.View.Thread.class}) @NotBlank(groups = {
                        FeedbackScoreBatch.View.Thread.class}) String threadId,
        @JsonView({FeedbackScoreBatch.View.Tracing.class,
                FeedbackScoreBatch.View.Thread.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
        @JsonIgnore UUID projectId,
        @JsonView({FeedbackScoreBatch.View.Tracing.class, FeedbackScoreBatch.View.Thread.class}) @NotBlank String name,
        @JsonView({FeedbackScoreBatch.View.Tracing.class, FeedbackScoreBatch.View.Thread.class}) String categoryName,
        @JsonView({FeedbackScoreBatch.View.Tracing.class,
                FeedbackScoreBatch.View.Thread.class}) @NotNull @DecimalMax(MAX_FEEDBACK_SCORE_VALUE) @DecimalMin(MIN_FEEDBACK_SCORE_VALUE) BigDecimal value,
        @JsonView({FeedbackScoreBatch.View.Tracing.class, FeedbackScoreBatch.View.Thread.class}) String reason,
        @JsonView({FeedbackScoreBatch.View.Tracing.class,
                FeedbackScoreBatch.View.Thread.class}) @NotNull ScoreSource source){
}
