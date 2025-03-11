package com.comet.opik.api;

import com.comet.opik.domain.SpanType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

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
public record Span(

        @JsonView( {
                Span.View.Public.class, Span.View.Write.class}) UUID id,
        @JsonView({
                Span.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
        @JsonView({Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID projectId,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) @NotNull UUID traceId,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) UUID parentSpanId,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) @NotBlank String name,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) @NotNull SpanType type,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) @NotNull Instant startTime,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) Instant endTime,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) JsonNode input,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) JsonNode output,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) JsonNode metadata,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) String model,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) String provider,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) Set<String> tags,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) Map<String, Integer> usage,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) ErrorInfo errorInfo,
        @JsonView({Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({
                Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScore> feedbackScores,
        @JsonView({Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<Comment> comments,
        @JsonView({Span.View.Public.class, Span.View.Write.class}) @DecimalMin("0.0") BigDecimal totalEstimatedCost,
        String totalEstimatedCostVersion,
        @JsonView({
                Span.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Duration in milliseconds as a decimal number to support sub-millisecond precision") Double duration){

    @Builder(toBuilder = true)
    public record SpanPage(
            @JsonView(Span.View.Public.class) int page,
            @JsonView(Span.View.Public.class) int size,
            @JsonView(Span.View.Public.class) long total,
            @JsonView(Span.View.Public.class) List<Span> content,
            @JsonView(Span.View.Public.class) List<String> sortableBy) implements com.comet.opik.api.Page<Span> {
        public static SpanPage empty(int page) {
            return new SpanPage(page, 0, 0, List.of(), List.of());
        }
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
