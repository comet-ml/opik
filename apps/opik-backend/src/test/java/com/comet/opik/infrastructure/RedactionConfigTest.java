package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redaction.RedactionService;
import io.dropwizard.jersey.validation.Validators;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.UncheckedIOException;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redaction Config Validation Test")
class RedactionConfigTest {

    private static final String EMPTY_WHEN_ENABLED = "redaction.rules must not be empty when redaction.enabled=true";

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

    @ParameterizedTest(name = "rules={0}")
    @MethodSource
    @DisplayName("enabled with no rules fails startup rather than masking nothing while appearing to be on")
    void enabledWithNoRulesFailsStartup(String rules) {
        // The contract config.yml documents. Asserted here because the two have disagreed: the key's own
        // comment also claimed [] was a no-op when enabled, which this rejects. Pinned to the message rather
        // than isNotEmpty(), which would also pass on an unrelated constraint and prove nothing about this one.
        assertThat(validator.validate(config(true, rules)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(EMPTY_WHEN_ENABLED);
    }

    static Stream<String> enabledWithNoRulesFailsStartup() {
        return Stream.of("[]", "  ", "");
    }

    @Test
    @DisplayName("enabled with a rule passes and compiles to that rule")
    void enabledWithARulePasses() {
        var config = config(true, ONE_RULE);

        assertThat(validator.validate(config)).isEmpty();
        assertThat(config.compile().rules()).hasSize(1);
        assertThat(config.compile().apply("mail john.doe@example.com now")).isEqualTo("mail [EMAIL] now");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a malformed rule set validates clean and is reported by startup instead")
    void aMalformedRuleSetIsLeftForStartupToReport(String label, String rules,
            Class<? extends Throwable> expected, String messageFragment) {

        var config = config(true, rules);

        // The validator must not throw: Hibernate Validator reports that as "HV000090: Unable to access
        // isConfiguredWhenEnabled" with the cause discarded, which is less use than the message below.
        assertThat(validator.validate(config)).isEmpty();

        // And startup must actually report it, asserted through the constructor that runs at startup - that
        // half was previously only claimed in a comment.
        assertThatThrownBy(() -> new RedactionService(config))
                .isInstanceOf(expected)
                .hasMessageContaining(messageFragment);
    }

    static Stream<Arguments> aMalformedRuleSetIsLeftForStartupToReport() {
        return Stream.of(
                Arguments.of("not json at all", "not json",
                        UncheckedIOException.class, "Unrecognized token"),
                Arguments.of("a rule with no regex", "[{\"replace\":\"[X]\"}]",
                        IllegalArgumentException.class, "redaction.rules[0].regex must not be blank"),
                Arguments.of("a rule whose regex is blank", "[{\"regex\":\"\",\"replace\":\"[X]\"}]",
                        IllegalArgumentException.class, "redaction.rules[0].regex must not be blank"),
                Arguments.of("a second rule whose regex is blank, named by its index",
                        "[{\"regex\":\"a\"},{\"regex\":\" \"}]",
                        IllegalArgumentException.class, "redaction.rules[1].regex must not be blank"),
                Arguments.of("a pattern that will not compile", "[{\"regex\":\"[unclosed\",\"replace\":\"[X]\"}]",
                        PatternSyntaxException.class, "Unclosed character class"));
    }
}
