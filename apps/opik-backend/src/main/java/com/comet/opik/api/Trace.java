package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Trace(
        @JsonView( {
                Trace.View.Public.class, Trace.View.Write.class}) UUID id,
        @JsonView({
                Trace.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID projectId,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) String name,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) @NotNull Instant startTime,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) Instant endTime,
        @Schema(implementation = JsonListString.class) @JsonView({Trace.View.Public.class,
                Trace.View.Write.class}) JsonNode input,
        @Schema(implementation = JsonListString.class) @JsonView({Trace.View.Public.class,
                Trace.View.Write.class}) JsonNode output,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) JsonNode metadata,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) Set<String> tags,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) ErrorInfo errorInfo,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, Long> usage,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) Instant lastUpdatedAt,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScore> feedbackScores,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<Comment> comments,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<GuardrailsValidation> guardrailsValidations,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCost,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) int spanCount,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Duration in milliseconds as a decimal number to support sub-millisecond precision") Double duration,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) String threadId){

    @Builder(toBuilder = true)
    public record TracePage(
            @JsonView(Trace.View.Public.class) int page,
            @JsonView(Trace.View.Public.class) int size,
            @JsonView(Trace.View.Public.class) long total,
            @JsonView(Trace.View.Public.class) List<Trace> content,
            @JsonView(Trace.View.Public.class) List<String> sortableBy) implements com.comet.opik.api.Page<Trace> {

        public static TracePage empty(int page, List<String> sortableBy) {
            return new TracePage(page, 0, 0, List.of(), sortableBy);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public enum TraceField {

        NAME("name"),
        START_TIME("start_time"),
        END_TIME("end_time"),
        INPUT("input"),
        OUTPUT("output"),
        METADATA("metadata"),
        TAGS("tags"),
        ERROR_INFO("error_info"),
        USAGE("usage"),
        CREATED_AT("created_at"),
        CREATED_BY("created_by"),
        LAST_UPDATED_BY("last_updated_by"),
        FEEDBACK_SCORES("feedback_scores"),
        COMMENTS("comments"),
        GUARDRAILS_VALIDATIONS("guardrails_validations"),
        TOTAL_ESTIMATED_COST("total_estimated_cost"),
        SPAN_COUNT("span_count"),
        DURATION("duration"),
        THREAD_ID("thread_id");

        @JsonValue
        private final String value;

    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
