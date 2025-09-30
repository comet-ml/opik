package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Webhook(
        @JsonView( {
                Alert.View.Public.class, Alert.View.Write.class}) UUID id,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) String name,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotBlank String url,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotBlank @Size(max = 250) String secretToken,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) Map<@NotBlank String, @NotBlank String> headers,

        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) Instant createdAt,

        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) String createdBy,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){
}
