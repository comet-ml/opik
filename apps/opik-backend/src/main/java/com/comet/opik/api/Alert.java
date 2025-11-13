package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Alert(
        @JsonView( {
                Alert.View.Public.class, Alert.View.Write.class}) UUID id,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Size(max = 255) String name,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @JsonProperty(defaultValue = "true") Boolean enabled,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) AlertType alertType,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) Map<String, String> metadata,

        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) @Valid @NotNull Webhook webhook,

        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) List<@Valid AlertTrigger> triggers,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonIgnore String workspaceId){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlertPage(
            @JsonView( {
                    Alert.View.Public.class}) int page,
            @JsonView({Alert.View.Public.class}) int size,
            @JsonView({Alert.View.Public.class}) long total,
            @JsonView({Alert.View.Public.class}) List<Alert> content,
            @JsonView({Alert.View.Public.class}) List<String> sortableBy) implements Page<Alert>{

        public static AlertPage empty(int page, List<String> sortableBy) {
            return new AlertPage(page, 0, 0, List.of(), sortableBy);
        }
    }
}
