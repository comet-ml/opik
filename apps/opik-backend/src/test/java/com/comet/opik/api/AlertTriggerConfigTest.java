package com.comet.opik.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.comet.opik.api.AlertTriggerConfig.LEGACY_WINDOW_SECONDS_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Alert Trigger Config Test")
class AlertTriggerConfigTest {

    @Test
    @DisplayName("the legacy window key is promoted to the current one")
    void promotesTheLegacyWindowKey() {
        var normalized = AlertTriggerConfig.withNormalizedWindow(
                Map.of(LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900"));

        assertThat(normalized).containsEntry(WINDOW_CONFIG_KEY, "900");
    }

    @Test
    @DisplayName("a config already carrying the current key is returned untouched")
    void leavesTheCurrentKeyAlone() {
        var configValue = Map.of(WINDOW_CONFIG_KEY, "300", LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900");

        assertThat(AlertTriggerConfig.withNormalizedWindow(configValue))
                .containsEntry(WINDOW_CONFIG_KEY, "300");
    }

    @Test
    @DisplayName("a null config value does not raise from inside normalization")
    void toleratesNullValues() {
        // Reached on the read path too, so this is not only about request bodies: a malformed value must
        // surface as the 400 the validation produces, never as a 500 thrown here.
        var configValue = new HashMap<String, String>();
        configValue.put(LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900");
        configValue.put("name", null);

        assertThatCode(() -> AlertTriggerConfig.withNormalizedWindow(configValue)).doesNotThrowAnyException();
        assertThat(AlertTriggerConfig.withNormalizedWindow(configValue))
                .containsEntry(WINDOW_CONFIG_KEY, "900");
    }

    @Test
    @DisplayName("a null config value map is passed through")
    void toleratesANullMap() {
        assertThat(AlertTriggerConfig.withNormalizedWindow(null)).isNull();
    }
}
