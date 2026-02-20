package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.math.BigDecimal;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.MAX_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.MIN_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Accessors(fluent = true, chain = true)
@Getter(onMethod_ = {@JsonProperty})
public abstract sealed class FeedbackScoreItem {

    @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used")
    private final String projectName;

    private final UUID projectId;

    @NotBlank private final String name;

    @NotNull @DecimalMax(MAX_FEEDBACK_SCORE_VALUE) @DecimalMin(MIN_FEEDBACK_SCORE_VALUE) private final BigDecimal value;

    private final String categoryName;

    private final String reason;

    @Min(0) @Max(1) private final Integer error;

    private final String errorReason;

    @NotNull private final ScoreSource source;

    private final String author;

    public abstract UUID id();

    public abstract String threadId();

    // Constructor for subclasses to use

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Accessors(fluent = true)
    @Getter(onMethod_ = {@JsonProperty})
    public static final class FeedbackScoreBatchItem extends FeedbackScoreItem {

        // entity (trace or span) id
        @NotNull private UUID id;

        @ConstructorProperties({"projectName", "projectId", "name", "categoryName", "value", "reason", "error",
                "errorReason", "source",
                "author", "id"})
        public FeedbackScoreBatchItem(String projectName, UUID projectId, String name, String categoryName,
                BigDecimal value, String reason, Integer error, String errorReason, ScoreSource source, String author,
                UUID id) {
            super(projectName, projectId, name, value, categoryName, reason, error, errorReason, source, author);
            this.id = id;
        }

        @Override
        @JsonIgnore
        public String threadId() {
            return null;
        }
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Accessors(fluent = true)
    @Getter(onMethod_ = {@JsonProperty})
    public static final class FeedbackScoreBatchItemThread extends FeedbackScoreItem {

        @NotBlank private String threadId;

        @JsonIgnore
        private UUID id;

        @ConstructorProperties({"projectName", "projectId", "name", "categoryName", "value", "reason", "error",
                "errorReason",
                "source", "author", "threadId"})
        public FeedbackScoreBatchItemThread(String projectName, UUID projectId, String name, String categoryName,
                BigDecimal value, String reason, Integer error, String errorReason, ScoreSource source, String author,
                String threadId) {
            super(projectName, projectId, name, value, categoryName, reason, error, errorReason, source, author);
            this.threadId = threadId;
        }

        @Override
        @JsonIgnore
        public UUID id() {
            return id;
        }
    }

}
