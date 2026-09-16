package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.HashMap;
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
                Alert.View.Write.class}) Map<String, String> configValue,

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
    // Configs written before the key settled on "window" store it here; read-only, never written.
    public static final String LEGACY_WINDOW_SECONDS_CONFIG_KEY = "window_seconds";
    public static final String NAME_CONFIG_KEY = "name";
    public static final String OPERATOR_CONFIG_KEY = "operator";
    // Comma-separated GuardrailType names (e.g. "PII,TOPIC"); empty/absent means all types.
    public static final String GUARDRAIL_TYPES_CONFIG_KEY = "guardrail_types";

    /**
     * The config value with the window under {@link #WINDOW_CONFIG_KEY}, taking it from the legacy key when
     * that is where it was stored. Applied as configs are read out of persistence, so the legacy spelling
     * never reaches a consumer: the alerts editor reads only {@code window} and drops a config it cannot
     * read, which would delete the condition on the next save.
     */
    public static Map<String, String> withNormalizedWindow(Map<String, String> configValue) {
        if (configValue == null
                || StringUtils.isNotBlank(configValue.get(WINDOW_CONFIG_KEY))
                || StringUtils.isBlank(configValue.get(LEGACY_WINDOW_SECONDS_CONFIG_KEY))) {
            return configValue;
        }
        var normalized = new HashMap<>(configValue);
        normalized.put(WINDOW_CONFIG_KEY, configValue.get(LEGACY_WINDOW_SECONDS_CONFIG_KEY));
        return Map.copyOf(normalized);
    }
}
