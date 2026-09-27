package com.comet.opik.infrastructure;

import org.junit.jupiter.api.Test;

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
}
