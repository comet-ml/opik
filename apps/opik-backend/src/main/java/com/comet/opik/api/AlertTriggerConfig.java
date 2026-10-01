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
import java.util.Collections;
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
        // Not Map.copyOf: it rejects null values, and a config value of null is a malformed request that
        // belongs in the 400 the validation already produces, not a 500 raised from inside this helper.
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * The canonical spelling of {@code operator} — the comparison symbol — or {@code null} when the value is
     * not an operator at all.
     *
     * <p>Both the enum name ({@code less_than}) and the symbol ({@code <}) reach persistence today, because
     * nothing ever canonicalised the value on the way in. They are not interchangeable downstream: the alerts
     * editor treats anything that is not exactly {@code <} as {@code >}, so a stored {@code less_than} both
     * displays as the opposite comparison and is written back as {@code >} the next time the alert is saved,
     * silently reversing what it fires on.
     *
     * <p>This is the one place that knows the accepted spellings; {@code MetricsAlertJob.Operator.fromString}
     * reads them from here rather than keeping a second list that could drift.
     */
    public static String normalizedOperator(String operator) {
        if (StringUtils.isBlank(operator)) {
            return null;
        }
        var value = operator.strip();
        if ("<".equals(value) || "less_than".equalsIgnoreCase(value)) {
            return "<";
        }
        if (">".equals(value) || "greater_than".equalsIgnoreCase(value)) {
            return ">";
        }
        return null;
    }

    /**
     * The config value with {@code operator} in its canonical spelling, left untouched when there is no
     * operator or it is not one this understands — an unknown value is the validation's business, not this
     * helper's. Applied alongside {@link #withNormalizedWindow}, on the way in and on the way out, so no
     * consumer has to know that more than one spelling was ever stored.
     */
    public static Map<String, String> withNormalizedOperator(Map<String, String> configValue) {
        if (configValue == null) {
            return null;
        }
        var canonical = normalizedOperator(configValue.get(OPERATOR_CONFIG_KEY));
        if (canonical == null || canonical.equals(configValue.get(OPERATOR_CONFIG_KEY))) {
            return configValue;
        }
        var normalized = new HashMap<>(configValue);
        normalized.put(OPERATOR_CONFIG_KEY, canonical);
        return Collections.unmodifiableMap(normalized);
    }

    /** Both normalisations, so a caller cannot remember one and forget the other. */
    public static Map<String, String> withNormalizedConfigValue(Map<String, String> configValue) {
        return withNormalizedOperator(withNormalizedWindow(configValue));
    }
}
