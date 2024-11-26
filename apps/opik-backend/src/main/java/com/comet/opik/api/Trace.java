package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
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
public record Trace(
        @JsonView( {
                Trace.View.Public.class,
                Trace.View.Write.class}) UUID id,
        @JsonView({
                Trace.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID projectId,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) @NotBlank String name,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) @NotNull Instant startTime,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) Instant endTime,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) JsonNode input,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) JsonNode output,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) JsonNode metadata,
        @JsonView({Trace.View.Public.class, Trace.View.Write.class}) Set<String> tags,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, Long> usage,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScore> feedbackScores,
        @JsonView({
                Trace.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCost){

    public record TracePage(
            @JsonView(Trace.View.Public.class) int page,
            @JsonView(Trace.View.Public.class) int size,
            @JsonView(Trace.View.Public.class) long total,
            @JsonView(Trace.View.Public.class) List<Trace> content) implements com.comet.opik.api.Page<Trace> {

        public static TracePage empty(int page) {
            return new TracePage(page, 0, 0, List.of());
        }
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
