package com.comet.opik.infrastructure;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureFlagsTest {

    private static FeatureFlags featureFlags(boolean guardrailsValidationsEnabled) {
        var configuration = new OpikConfiguration();
        configuration.getServiceToggles().setGuardrailsValidationsEnabled(guardrailsValidationsEnabled);
        return new FeatureFlags(configuration);
    }

    @Test
    void guardrailsValidationsOffIsForbidden() {
        var featureFlags = featureFlags(false);

        assertThat(featureFlags.isGuardrailsValidationsEnabled()).isFalse();
        assertThatThrownBy(featureFlags::checkGuardrailsValidationsEnabled)
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Guardrails validation service is not enabled");
    }

    @Test
    void guardrailsValidationsOnPasses() {
        var featureFlags = featureFlags(true);

        assertThat(featureFlags.isGuardrailsValidationsEnabled()).isTrue();
        assertThatCode(featureFlags::checkGuardrailsValidationsEnabled).doesNotThrowAnyException();
    }

    @Test
    void guardrailsTrainingOffIsForbiddenEvenWithValidationsOn() {
        var featureFlags = featureFlags(true);

        assertThatThrownBy(featureFlags::checkGuardrailsTrainingEnabled)
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Guardrail model training is not enabled");
    }

    @Test
    void guardrailsTrainingOnPasses() {
        var configuration = new OpikConfiguration();
        configuration.getServiceToggles().setGuardrailsTrainingEnabled(true);

        assertThatCode(new FeatureFlags(configuration)::checkGuardrailsTrainingEnabled).doesNotThrowAnyException();
    }
}
