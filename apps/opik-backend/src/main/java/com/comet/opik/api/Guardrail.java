package com.comet.opik.api;

import com.comet.opik.domain.GuardrailResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Guardrail(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @JsonView( {
                Guardrail.View.Public.class}) UUID id,

        // entity (trace or span) id
        @JsonView({
                Guardrail.View.Public.class, Guardrail.View.Write.class}) @NotNull UUID entityId,

        // secondary id used for grouping (guardrail span id)
        @JsonView({
                Guardrail.View.Public.class, Guardrail.View.Write.class}) @NotNull UUID secondaryId,

        @JsonView({
                Guardrail.View.Public.class,
                Guardrail.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,

        @JsonView({
                Guardrail.View.Public.class, Guardrail.View.Write.class}) @JsonIgnore UUID projectId,

        @JsonView({
                Guardrail.View.Public.class, Guardrail.View.Write.class}) @NotNull GuardrailType name,

        @JsonView({
                Guardrail.View.Public.class,
                Guardrail.View.Write.class}) @NotNull GuardrailResult result,

        @JsonView({
                Guardrail.View.Public.class,
                Guardrail.View.Write.class}) @Schema(implementation = JsonNode.class, ref = "JsonNode") @NotNull JsonNode config,

        @JsonView({
                Guardrail.View.Public.class,
                Guardrail.View.Write.class}) @Schema(implementation = JsonNode.class, ref = "JsonNode") @NotNull JsonNode details){

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
