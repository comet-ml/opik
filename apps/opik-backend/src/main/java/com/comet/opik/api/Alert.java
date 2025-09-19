package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonDeserialize(builder = Alert.AlertBuilder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Alert(
        @JsonView( {
                Alert.View.Public.class,
                Alert.View.Write.class}) @Schema(description = "Alert ID", example = "550e8400-e29b-41d4-a716-446655440000", accessMode = Schema.AccessMode.READ_ONLY) UUID id,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotBlank @Size(max = 255) @Schema(description = "Alert name", example = "High Error Rate Alert") String name,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Size(max = 1000) @Schema(description = "Alert description", example = "Alert triggered when error rate exceeds 5%") String description,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotBlank @Size(max = 50) @Schema(description = "Type of condition to monitor", example = "ERROR_RATE") String conditionType,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotNull @DecimalMin(value = "0.0", inclusive = true) @Schema(description = "Threshold value for the alert condition", example = "0.05") BigDecimal thresholdValue,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Schema(description = "Project ID to scope the alert to", example = "550e8400-e29b-41d4-a716-446655440001") UUID projectId,

        @JsonView({
                Alert.View.Public.class}) @Schema(description = "Workspace ID", accessMode = Schema.AccessMode.READ_ONLY) String workspaceId,

        @JsonView({
                Alert.View.Public.class}) @Schema(description = "Alert creation timestamp", accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(description = "User who created the alert", accessMode = Schema.AccessMode.READ_ONLY) String createdBy,

        @JsonView({
                Alert.View.Public.class}) @Schema(description = "Last update timestamp", accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(description = "User who last updated the alert", accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @JsonPOJOBuilder(withPrefix = "")
    public static class AlertBuilder {
    }

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlertPage(
            @JsonView( {
                    Alert.View.Public.class}) int page,
            @JsonView({Alert.View.Public.class}) int size,
            @JsonView({Alert.View.Public.class}) long total,
            @JsonView({Alert.View.Public.class}) List<Alert> content,
            @JsonView({Alert.View.Public.class}) List<String> sortableBy)
            implements
                Page<Alert>{

        public static AlertPage empty(int page, List<String> sortableBy) {
            return new AlertPage(page, 0, 0, List.of(), sortableBy);
        }
    }
}
