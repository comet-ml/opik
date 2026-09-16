package com.comet.opik.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static com.comet.opik.api.AlertTriggerConfig.THRESHOLD_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_IN_SECONDS_CONFIG_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertTriggerConfigTest {

    @Test
    void resolveWindowWhenConfigValueIsNullReturnsNull() {
        assertThat(AlertTriggerConfig.resolveWindow(null)).isNull();
    }

    @Test
    void resolveWindowWhenBothKeysAbsentReturnsNull() {
        assertThat(AlertTriggerConfig.resolveWindow(Map.of(THRESHOLD_CONFIG_KEY, "2"))).isNull();
    }

    @Test
    void resolveWindowWhenCanonicalWindowPresentReturnsCanonical() {
        assertThat(AlertTriggerConfig.resolveWindow(Map.of(
                WINDOW_CONFIG_KEY, "300",
                WINDOW_IN_SECONDS_CONFIG_KEY, "999"))).isEqualTo("300");
    }

    @Test
    void resolveWindowWhenOnlyAliasPresentReturnsAlias() {
        assertThat(AlertTriggerConfig.resolveWindow(Map.of(WINDOW_IN_SECONDS_CONFIG_KEY, "300")))
                .isEqualTo("300");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "  \n"})
    void resolveWindowWhenCanonicalWindowBlankFallsBackToAlias(String blankWindow) {
        Map<String, String> configValue = new HashMap<>();
        configValue.put(WINDOW_CONFIG_KEY, blankWindow);
        configValue.put(WINDOW_IN_SECONDS_CONFIG_KEY, "300");

        assertThat(AlertTriggerConfig.resolveWindow(configValue)).isEqualTo("300");
    }

    @Test
    void resolveWindowWhenCanonicalWindowHasSurroundingWhitespaceReturnsTrimmedValue() {
        assertThat(AlertTriggerConfig.resolveWindow(Map.of(WINDOW_CONFIG_KEY, "  300  ")))
                .isEqualTo("300");
    }

    @Test
    void requireWindowWhenBothKeysMissingMentionsBothAcceptedKeys() {
        assertThatThrownBy(() -> AlertTriggerConfig.requireWindow(
                Map.of(THRESHOLD_CONFIG_KEY, "2"), AlertTriggerConfigType.THRESHOLD_ERRORS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(WINDOW_CONFIG_KEY)
                .hasMessageContaining(WINDOW_IN_SECONDS_CONFIG_KEY);
    }

    @Test
    void requireWindowWhenConfigValueIsNullMentionsBothAcceptedKeys() {
        assertThatThrownBy(() -> AlertTriggerConfig.requireWindow(null, AlertTriggerConfigType.THRESHOLD_COST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(WINDOW_CONFIG_KEY)
                .hasMessageContaining(WINDOW_IN_SECONDS_CONFIG_KEY);
    }
}
