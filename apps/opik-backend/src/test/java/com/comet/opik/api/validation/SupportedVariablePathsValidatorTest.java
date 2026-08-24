package com.comet.opik.api.validation;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Save-time enforcement of the supported variable-path grammar. Recursive descent and filter predicates
 * are rejected because their evaluation cost grows with the size of the scored trace, on a scheduler
 * shared across workspaces; everything else JsonPath accepts stays allowed.
 * <p>
 * The supported cases are drawn from mappings actually in use, so the accepted grammar is pinned against
 * real rules rather than guesswork.
 */
class SupportedVariablePathsValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static LlmAsJudgeCode codeWith(Map<String, String> variables) {
        return new LlmAsJudgeCode(
                LlmAsJudgeModelParameters.builder().name("gpt-4o-mini").temperature(0.0).build(),
                List.of(),
                variables,
                List.of());
    }

    static Stream<Arguments> supportedMappings() {
        return Stream.of(
                arguments("plain nested", "output.response"),
                arguments("deep nested", "output.output.choices[0].message.content"),
                arguments("indexed", "input.messages[0].content"),
                arguments("indexed root", "input[1].text"),
                arguments("single-level wildcard", "output.output.results[*].content"),
                arguments("whole section", "output"),
                arguments("literal value, not a path", "some literal string"),
                // A literal is never handed to JsonPath, so the grammar does not constrain it.
                arguments("literal containing dots", "see ../docs for details"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedMappings")
    @DisplayName("supported mappings pass validation")
    void supported(String shape, String mapping) {
        var violations = validator.validate(codeWith(Map.of("variable", mapping)));

        assertThat(violations).isEmpty();
    }

    static Stream<Arguments> unsupportedMappings() {
        return Stream.of(
                arguments("recursive descent", "output..content", ".."),
                arguments("recursive wildcard", "output..*", ".."),
                arguments("chained descent", "output..a..b", ".."),
                arguments("filter predicate", "output.items[?(@.role == 'user')].content", "[?("),
                arguments("descent plus filter", "output..[?(@.leaf)]", ".."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMappings")
    @DisplayName("unsupported mappings are rejected, naming the variable and the construct")
    void unsupported(String shape, String mapping, String construct) {
        var violations = validator.validate(codeWith(Map.of("answer", mapping)));

        assertThat(violations).hasSize(1);
        var message = violations.iterator().next().getMessage();
        assertThat(message).contains("'answer'").contains(mapping).contains(construct);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMappings")
    @DisplayName("a supported mapping alongside an unsupported one still fails")
    void unsupportedAmongSupported(String shape, String mapping, String construct) {
        var violations = validator.validate(codeWith(Map.of(
                "fine", "output.response",
                "answer", mapping)));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("'answer'").doesNotContain("'fine'");
    }
}
