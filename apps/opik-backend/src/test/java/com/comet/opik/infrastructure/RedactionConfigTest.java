package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redaction Config Validation Test")
class RedactionConfigTest {

    private static final String ONE_RULE = """
            [{"regex":"(?<![\\\\w.+-])[\\\\w.+-]+@[\\\\w-]+\\\\.[\\\\w.]+","replace":"[EMAIL]"}]""";

    private final Validator validator = Validators.newValidator();

    private static RedactionConfig config(boolean enabled, String rules) {
        var config = new RedactionConfig();
        config.setEnabled(enabled);
        config.setRules(rules);
        return config;
    }

    @Test
    @DisplayName("the shipped defaults pass, since an empty set is only meaningful while the feature is off")
    void theShippedDefaultsPass() {
        assertThat(validator.validate(config(false, "[]"))).isEmpty();
    }

    @Test
    @DisplayName("enabled with no rules fails startup rather than masking nothing while appearing to be on")
    void enabledWithNoRulesFailsStartup() {
        // The contract config.yml documents. Asserted here because the two have disagreed: the key's own
        // comment also claimed [] was a no-op when enabled, which this rejects.
        assertThat(validator.validate(config(true, "[]")))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("redaction.rules must not be empty when redaction.enabled=true");

        assertThat(validator.validate(config(true, "  "))).isNotEmpty();
    }

    @Test
    @DisplayName("enabled with a rule passes and compiles to that rule")
    void enabledWithARulePasses() {
        var config = config(true, ONE_RULE);

        assertThat(validator.validate(config)).isEmpty();
        assertThat(config.compile().rules()).hasSize(1);
        assertThat(config.compile().apply("mail john.doe@example.com now")).isEqualTo("mail [EMAIL] now");
    }

    @Test
    @DisplayName("a malformed rule set is left for startup to report, not turned into a validation message")
    void aMalformedRuleSetIsLeftForStartup() {
        // The validator must not throw: Hibernate Validator reports that as "HV000090: Unable to access
        // isConfiguredWhenEnabled" with the cause discarded, hiding the real error.
        assertThat(validator.validate(config(true, "not json"))).isEmpty();
        assertThat(validator.validate(config(true, "[{\"replace\":\"[X]\"}]"))).isEmpty();
    }
}
