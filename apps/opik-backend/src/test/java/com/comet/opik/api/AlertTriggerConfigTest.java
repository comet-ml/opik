package com.comet.opik.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.comet.opik.api.AlertTriggerConfig.LEGACY_WINDOW_SECONDS_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.NAME_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.OPERATOR_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.THRESHOLD_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Alert Trigger Config Test")
class AlertTriggerConfigTest {

    @Test
    @DisplayName("the legacy window key is promoted, and everything else is carried through")
    void promotesTheLegacyWindowKey() {
        // Asserted as a whole map: entry-wise checks would pass an implementation that promoted the window
        // and dropped the rest, which is the failure that would actually hurt -- this runs over every config
        // read out of persistence, not only over the window.
        var configValue = Map.of(
                LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900",
                THRESHOLD_CONFIG_KEY, "0.5",
                NAME_CONFIG_KEY, "quality",
                OPERATOR_CONFIG_KEY, "<");

        assertThat(AlertTriggerConfig.withNormalizedWindow(configValue))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        WINDOW_CONFIG_KEY, "900",
                        LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900",
                        THRESHOLD_CONFIG_KEY, "0.5",
                        NAME_CONFIG_KEY, "quality",
                        OPERATOR_CONFIG_KEY, "<"));
    }

    @Test
    @DisplayName("a config already carrying the current key is returned unchanged")
    void leavesTheCurrentKeyAlone() {
        var configValue = Map.of(
                WINDOW_CONFIG_KEY, "300",
                LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900",
                THRESHOLD_CONFIG_KEY, "0.5");

        assertThat(AlertTriggerConfig.withNormalizedWindow(configValue))
                .containsExactlyInAnyOrderEntriesOf(configValue);
    }

    @Test
    @DisplayName("a config with no window at all is returned unchanged")
    void leavesAConfigWithNoWindowAlone() {
        var configValue = Map.of(THRESHOLD_CONFIG_KEY, "0.5", NAME_CONFIG_KEY, "quality");

        assertThat(AlertTriggerConfig.withNormalizedWindow(configValue))
                .containsExactlyInAnyOrderEntriesOf(configValue);
    }

    @Test
    @DisplayName("a null config value does not raise from inside normalization")
    void toleratesNullValues() {
        // Reached on the read path too, so this is not only about request bodies: a malformed value must
        // surface as the 400 the validation produces, never as a 500 thrown here.
        var configValue = new HashMap<String, String>();
        configValue.put(LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900");
        configValue.put(THRESHOLD_CONFIG_KEY, "0.5");
        configValue.put(NAME_CONFIG_KEY, null);

        assertThatCode(() -> AlertTriggerConfig.withNormalizedWindow(configValue)).doesNotThrowAnyException();

        var expected = new HashMap<>(configValue);
        expected.put(WINDOW_CONFIG_KEY, "900");
        assertThat(AlertTriggerConfig.withNormalizedWindow(configValue))
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("a null config value map is passed through")
    void toleratesANullMap() {
        assertThat(AlertTriggerConfig.withNormalizedWindow(null)).isNull();
    }
}
