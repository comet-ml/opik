package com.comet.opik.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static com.comet.opik.api.AlertTriggerConfig.LEGACY_WINDOW_SECONDS_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.NAME_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.OPERATOR_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.THRESHOLD_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

    static Stream<Arguments> operatorSpellings() {
        return Stream.of(
                arguments("<", "<"),
                arguments(">", ">"),
                // The enum-name spelling found in stored rows. Nothing canonicalised the value on the way in,
                // so both reached persistence, and the alerts editor reads anything that is not exactly "<"
                // as ">" — displaying, and on the next save storing, the opposite comparison (OPIK-8555).
                arguments("less_than", "<"),
                arguments("greater_than", ">"),
                arguments("LESS_THAN", "<"),
                arguments("GREATER_THAN", ">"),
                arguments("  less_than  ", "<"),
                // Not operators; left for the validation to reject rather than guessed at here.
                arguments("not_an_operator", null),
                arguments("", null),
                arguments(null, null));
    }

    @ParameterizedTest
    @MethodSource("operatorSpellings")
    @DisplayName("every accepted operator spelling canonicalises to its symbol")
    void normalizesOperatorSpellings(String stored, String expected) {
        assertThat(AlertTriggerConfig.normalizedOperator(stored)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the config value carries the canonical operator, leaving the rest untouched")
    void withNormalizedOperatorRewritesOnlyTheOperator() {
        var configValue = Map.of(
                OPERATOR_CONFIG_KEY, "less_than",
                THRESHOLD_CONFIG_KEY, "0.5",
                WINDOW_CONFIG_KEY, "900");

        assertThat(AlertTriggerConfig.withNormalizedOperator(configValue))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        OPERATOR_CONFIG_KEY, "<",
                        THRESHOLD_CONFIG_KEY, "0.5",
                        WINDOW_CONFIG_KEY, "900"));
    }

    @Test
    @DisplayName("an unrecognised operator is left alone for the validation to reject")
    void withNormalizedOperatorLeavesAnUnknownValue() {
        var configValue = Map.of(OPERATOR_CONFIG_KEY, "not_an_operator");

        assertThat(AlertTriggerConfig.withNormalizedOperator(configValue))
                .containsExactlyInAnyOrderEntriesOf(configValue);
    }

    @Test
    @DisplayName("the combined helper applies both normalisations in one pass")
    void withNormalizedConfigValueAppliesBoth() {
        var configValue = Map.of(
                LEGACY_WINDOW_SECONDS_CONFIG_KEY, "900",
                OPERATOR_CONFIG_KEY, "less_than");

        assertThat(AlertTriggerConfig.withNormalizedConfigValue(configValue))
                .containsEntry(WINDOW_CONFIG_KEY, "900")
                .containsEntry(OPERATOR_CONFIG_KEY, "<");
    }
}
