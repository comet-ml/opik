package com.comet.opik.infrastructure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redaction Config")
class RedactionConfigTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static RedactionConfig config(boolean enabled, List<String> maskFields) {
        var config = new RedactionConfig();
        config.setEnabled(enabled);
        config.setMaskFields(maskFields);
        return config;
    }

    @Test
    @DisplayName("enabled with nothing to mask fails startup rather than quietly masking nothing")
    void enabledWithNothingToMaskFailsStartup() {
        // The failure this prevents is silent: every response comes back exactly as stored while the deployment
        // believes its content is protected, and nothing about that is visible from the outside.
        var violations = validator.validate(config(true, List.of()));

        assertThat(violations)
                .extracting(v -> v.getMessage())
                .contains("redaction.maskFields must not be empty when redaction.enabled=true");
    }

    @Test
    @DisplayName("enabled with fields is accepted")
    void enabledWithFieldsIsAccepted() {
        assertThat(validator.validate(config(true, List.of("content")))).isEmpty();
    }

    @Test
    @DisplayName("disabled with no fields is accepted, since that is the default and masks nothing by design")
    void disabledWithNoFieldsIsAccepted() {
        assertThat(validator.validate(config(false, List.of()))).isEmpty();
    }

    @Test
    @DisplayName("disabled with fields is accepted, so a deployment can stage its config before switching on")
    void disabledWithFieldsIsAccepted() {
        assertThat(validator.validate(config(false, List.of("content")))).isEmpty();
    }

    @Test
    @DisplayName("a blank field name fails startup, since it would mask nothing and mask it silently")
    void aBlankFieldNameFailsStartup() {
        assertThat(validator.validate(config(true, List.of("content", " ")))).isNotEmpty();
    }

    @Test
    @DisplayName("a blank replacement fails startup, since masking to an empty string is indistinguishable from loss")
    void aBlankReplacementFailsStartup() {
        var config = config(true, List.of("content"));
        config.setReplacement("");

        assertThat(validator.validate(config)).isNotEmpty();
    }

    @Test
    @DisplayName("the default config is valid and masks nothing")
    void theDefaultConfigIsValidAndMasksNothing() {
        var config = new RedactionConfig();

        assertThat(validator.validate(config)).isEmpty();
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.compile().isNoOp()).isTrue();
    }
}
