package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlertCreateRequest(
        @JsonView(AlertCreateRequest.View.Write.class) @NotBlank @Size(max = 255) @Schema(description = "Alert name", example = "High Error Rate Alert") String name,

        @JsonView(AlertCreateRequest.View.Write.class) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Size(max = 1000) @Schema(description = "Alert description", example = "Alert triggered when error rate exceeds 5%") String description,

        @JsonView(AlertCreateRequest.View.Write.class) @NotBlank @Size(max = 50) @Schema(description = "Type of condition to monitor", example = "ERROR_RATE") String conditionType,

        @JsonView(AlertCreateRequest.View.Write.class) @NotNull @DecimalMin(value = "0.0", inclusive = true) @Schema(description = "Threshold value for the alert condition", example = "0.05") BigDecimal thresholdValue,

        @JsonView(AlertCreateRequest.View.Write.class) @Schema(description = "Project ID to scope the alert to", example = "550e8400-e29b-41d4-a716-446655440001") UUID projectId) {

    public static class View {
        public static class Write {
        }
    }

    public Alert toAlert() {
        return Alert.builder()
                .name(name)
                .description(description)
                .conditionType(conditionType)
                .thresholdValue(thresholdValue)
                .projectId(projectId)
                .build();
    }
}
