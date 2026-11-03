package com.comet.opik.infrastructure;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomChartsConfigTest {

    @ParameterizedTest
    @CsvSource({
            "true, true, true",
            "true, false, false",
            "false, true, false",
            "false, false, false"})
    void isEnabledNeedsOllieAndTheToggle(boolean ollieEnabled, boolean enabled, boolean expected) {
        var config = new CustomChartsConfig();
        config.setEnabled(enabled);
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(ollieEnabled);

        assertThat(config.isEnabled(serviceToggles)).isEqualTo(expected);
    }
}
