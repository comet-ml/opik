package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueCreate(
        @Schema(description = "Queue name", example = "Customer Support Q4 Review")
        @NotBlank
        String name,

        @Schema(description = "Queue description", example = "Review customer support agent responses for Q4")
        String description,

        @Schema(description = "Template ID for reusable configurations")
        UUID templateId,

        @Schema(description = "Fields visible to SMEs", example = "[\"input\", \"output\", \"timestamp\"]")
        List<String> visibleFields,

        @Schema(description = "Required annotation metrics", example = "[\"rating\"]")
        List<String> requiredMetrics,

        @Schema(description = "Optional annotation metrics", example = "[\"comment\"]")
        List<String> optionalMetrics,

        @Schema(description = "Instructions for SMEs")
        String instructions,

        @Schema(description = "Due date for queue completion")
        Instant dueDate
) {
}