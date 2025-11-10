package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetExpansion(
        @JsonView( {
                DatasetExpansion.View.Write.class}) @NotBlank @Schema(description = "The model to use for synthetic data generation", example = "gpt-4") String model,
        @JsonView({
                DatasetExpansion.View.Write.class}) @Min(1) @Max(200) @Schema(description = "Number of synthetic samples to generate", example = "10") int sampleCount,
        @JsonView({
                DatasetExpansion.View.Write.class}) @Schema(description = "Fields to preserve patterns from original data", example = "[\"input\", \"expected_output\"]") List<String> preserveFields,
        @JsonView({
                DatasetExpansion.View.Write.class}) @Schema(description = "Additional instructions for data variation", example = "Create variations that test edge cases") String variationInstructions,
        @JsonView({
                DatasetExpansion.View.Write.class}) @Schema(description = "Custom prompt to use for generation instead of auto-generated one") String customPrompt){

    public static class View {
        public static class Write {
        }
    }
}