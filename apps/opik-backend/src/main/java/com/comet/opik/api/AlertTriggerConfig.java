package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlertTriggerConfig(
        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) UUID id,

        @JsonView({Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID alertTriggerId,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotNull AlertTriggerConfigType type,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Schema(description = "Trigger configuration map. Threshold configs accept both 'window' and 'window_in_seconds'; 'window' takes precedence when both are provided. A blank 'window' falls back to 'window_in_seconds'.") Map<String, String> configValue,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Schema(description = "Groups configs within a trigger: same group_index means AND between configs, different group_index means OR between groups. Null means a legacy/singleton group of one config. Always null for scope:project configs.") Integer groupIndex,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,

        @JsonView({Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,

        @JsonView({Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    public static final String PROJECT_IDS_CONFIG_KEY = "project_ids";
    public static final String THRESHOLD_CONFIG_KEY = "threshold";
    public static final String WINDOW_CONFIG_KEY = "window";
    // Documented REST alias for WINDOW_CONFIG_KEY; stored configs may use either spelling.
    public static final String WINDOW_IN_SECONDS_CONFIG_KEY = "window_in_seconds";
    public static final String NAME_CONFIG_KEY = "name";
    public static final String OPERATOR_CONFIG_KEY = "operator";
    // Comma-separated GuardrailType names (e.g. "PII,TOPIC"); empty/absent means all types.
    public static final String GUARDRAIL_TYPES_CONFIG_KEY = "guardrail_types";

    @Nullable public static String resolveWindow(@Nullable Map<String, String> configValue) {
        if (configValue == null) {
            return null;
        }
        String window = StringUtils.trimToNull(configValue.get(WINDOW_CONFIG_KEY));
        if (window != null) {
            return window;
        }
        return StringUtils.trimToNull(configValue.get(WINDOW_IN_SECONDS_CONFIG_KEY));
    }

    public static String requireWindow(@Nullable Map<String, String> configValue, AlertTriggerConfigType type) {
        String window = resolveWindow(configValue);
        if (window == null) {
            throw new IllegalArgumentException(
                    "Missing config value for key '%s' or '%s' in trigger of type '%s'"
                            .formatted(WINDOW_CONFIG_KEY, WINDOW_IN_SECONDS_CONFIG_KEY, type));
        }
        return window;
    }
}
