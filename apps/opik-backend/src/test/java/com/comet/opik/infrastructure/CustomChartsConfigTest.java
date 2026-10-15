package com.comet.opik.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomChartsConfigTest {

    @Test
    void allowlistReflectsTheBoundValueEvenIfReadBeforeBinding() {
        var config = new CustomChartsConfig();
        // Lombok's toString, equals and hashCode read the config before Jackson binds it; that must not stick.
        assertThat(config.toString()).isNotEmpty();

        config.setEnabledWorkspaces(" ws-1, ,ws-2 ");

        assertThat(config.enabledWorkspaceIds()).containsExactlyInAnyOrder("ws-1", "ws-2");
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, true",
            "true, false, false",
            "false, true, false",
            "false, false, false"})
    void isEnabledForNeedsOllieAndTheAllowlist(boolean ollieEnabled, boolean allowlisted, boolean expected) {
        var workspaceId = UUID.randomUUID().toString();
        var config = new CustomChartsConfig();
        config.setEnabledWorkspaces(allowlisted ? workspaceId : UUID.randomUUID().toString());
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(ollieEnabled);

        assertThat(config.isEnabledFor(serviceToggles, workspaceId)).isEqualTo(expected);
    }
}
