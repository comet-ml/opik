package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import javax.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlertTrigger(
        @JsonView( {
                Alert.View.Public.class, Alert.View.Write.class}) UUID id,

        @JsonView({Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID alertId,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotNull AlertEventType eventType,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Nullable List<@Valid AlertTriggerConfig> triggerConfigs,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy){
}
