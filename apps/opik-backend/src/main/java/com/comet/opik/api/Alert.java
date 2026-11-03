package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) UUID id,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Size(max = 255) String name,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @JsonProperty(defaultValue = "true") Boolean enabled,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) AlertType alertType,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) Map<String, String> metadata,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Size(max = MAX_CUSTOM_FIELDS) @Schema(description = """
                        Optional user-defined key/value pairs, added as top-level keys of the alert's \
                        EventBridge event detail. Keys must not collide with the event's own keys.\
                        """) Map<@NotBlank @Size(max = MAX_CUSTOM_FIELD_KEY_LENGTH) String, @NotBlank @Size(max = MAX_CUSTOM_FIELD_VALUE_LENGTH) String> customFields,

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
        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Schema(description = """
                        Optional project scope for this alert. \
                        When set, the alert is scoped to the specified project. \
                        Do NOT also provide a 'scope:project' trigger config — the system will create it automatically from this field. \
                        Sending both project_id and a scope:project trigger config will result in an error.\
                        """) UUID projectId,
        @JsonIgnore String workspaceId) {

    public static final int MAX_CUSTOM_FIELDS = 20;
    public static final int MAX_CUSTOM_FIELD_KEY_LENGTH = 64;
    public static final int MAX_CUSTOM_FIELD_VALUE_LENGTH = 255;

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
            @JsonView({
                    Alert.View.Public.class}) int page,
            @JsonView({Alert.View.Public.class}) int size,
            @JsonView({Alert.View.Public.class}) long total,
            @JsonView({Alert.View.Public.class}) List<Alert> content,
            @JsonView({Alert.View.Public.class}) List<String> sortableBy) implements Page<Alert> {

        public static AlertPage empty(int page, List<String> sortableBy) {
            return new AlertPage(page, 0, 0, List.of(), sortableBy);
        }
    }
}
