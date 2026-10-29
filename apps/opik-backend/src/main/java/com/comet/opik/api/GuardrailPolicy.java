package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailPolicy(
        @JsonView({
                GuardrailPolicy.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({GuardrailPolicy.View.Public.class,
                GuardrailPolicy.View.Write.class}) @Schema(description = "The SDK names policies by name, so it is unique in the workspace, ignoring case") @NotBlank @Size(max = 255, message = "name cannot exceed 255 characters") String name,
        @JsonView({GuardrailPolicy.View.Public.class,
                GuardrailPolicy.View.Write.class}) @Nullable @Size(max = 1000, message = "description cannot exceed 1000 characters") String description,
        @JsonView({GuardrailPolicy.View.Public.class,
                GuardrailPolicy.View.Write.class}) @Schema(description = "on_request applies the policy when the SDK names it, global applies it to every guardrail built from stored policies in the workspace. Missing or null means on_request", defaultValue = "on_request") @Nullable GuardrailExecutionScope executionScope,
        @JsonView({GuardrailPolicy.View.Public.class,
                GuardrailPolicy.View.Write.class}) @Schema(description = "At most one rule per type") @NotEmpty List<@NotNull @Valid GuardrailPolicyRule> rules,
        @JsonView({
                GuardrailPolicy.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                GuardrailPolicy.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                GuardrailPolicy.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                GuardrailPolicy.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GuardrailPolicyPage(
            @JsonView({
                    GuardrailPolicy.View.Public.class}) List<GuardrailPolicy> content,
            @JsonView({GuardrailPolicy.View.Public.class}) int page,
            @JsonView({GuardrailPolicy.View.Public.class}) int size,
            @JsonView({GuardrailPolicy.View.Public.class}) long total,
            @JsonView({GuardrailPolicy.View.Public.class}) List<String> sortableBy) implements Page<GuardrailPolicy> {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GuardrailPolicyRetrieve(
            @Schema(description = "Names of the policies to return. Every global policy is returned as well") @Nullable @Size(max = 1000) Set<@NotBlank String> names) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GuardrailPolicyRetrieveResponse(
            @JsonView({
                    GuardrailPolicy.View.Public.class}) List<GuardrailPolicy> policies) {
    }
}
