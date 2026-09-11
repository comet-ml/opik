package com.comet.opik.domain.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PythonTemplateParser Test")
class PythonTemplateParserTest {

    private PythonTemplateParser parser;

    @BeforeEach
    void setUp() {
        parser = new PythonTemplateParser();
    }

    @Test
    @DisplayName("should extract variables from valid template")
    void extractVariables() {
        String template = "Hello {name}, you are {age} years old and live in {city}.";

        Set<String> variables = parser.extractVariables(template);

        assertThat(variables).containsExactlyInAnyOrder("name", "age", "city");
    }

    @Test
    @DisplayName("should return empty set for template without variables")
    void extractVariablesWhenNoVariablesReturnsEmptySet() {
        String template = "Hello world, this is a simple text without variables.";

        Set<String> variables = parser.extractVariables(template);

        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should ignore escaped braces (Python {{literal}})")
    void extractVariablesWhenEscapedBracesIgnoresThem() {
        String template = "Use {{literal}} braces and {actual_var} here.";

        Set<String> variables = parser.extractVariables(template);

        assertThat(variables).containsExactly("actual_var");
    }

    @Test
    @DisplayName("should handle null template")
    void extractVariablesWhenNullReturnsEmptySet() {
        Set<String> variables = parser.extractVariables(null);

        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should handle empty template")
    void extractVariablesWhenEmptyReturnsEmptySet() {
        Set<String> variables = parser.extractVariables("");

        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should handle blank template")
    void extractVariablesWhenBlankReturnsEmptySet() {
        Set<String> variables = parser.extractVariables("   ");

        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should deduplicate repeated variables")
    void extractVariablesWhenDuplicatesReturnsUniqueSet() {
        String template = "{name} said hello to {name} and {other}.";

        Set<String> variables = parser.extractVariables(template);

        assertThat(variables).containsExactlyInAnyOrder("name", "other");
    }

    @Test
    @DisplayName("should render template with context values")
    void render() {
        String template = "Hello {name}, you are {age} years old.";
        Map<String, Object> context = Map.of("name", "John", "age", 30);

        String result = parser.render(template, context);

        assertThat(result).isEqualTo("Hello John, you are 30 years old.");
    }

    @Test
    @DisplayName("should keep original placeholder when variable not in context")
    void renderWhenMissingVariableKeepsPlaceholder() {
        String template = "Hello {name}, you live in {city}.";
        Map<String, Object> context = Map.of("name", "John");

        String result = parser.render(template, context);

        assertThat(result).isEqualTo("Hello John, you live in {city}.");
    }

    @Test
    @DisplayName("should return empty string for null template")
    void renderWhenNullReturnsEmptyString() {
        String result = parser.render(null, Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should preserve escaped braces in rendered output")
    void renderWhenEscapedBracesPreservesThem() {
        String template = "Use {{literal}} and {var} here.";
        Map<String, Object> context = Map.of("var", "value");

        String result = parser.render(template, context);

        assertThat(result).isEqualTo("Use {{literal}} and value here.");
    }

    @Test
    @DisplayName("should handle template with no variables")
    void renderWhenNoVariablesReturnsOriginal() {
        String template = "Just plain text.";

        String result = parser.render(template, Map.of());

        assertThat(result).isEqualTo("Just plain text.");
    }

    @Test
    @DisplayName("should handle special regex characters in replacement values")
    void renderWhenSpecialCharsInValueHandlesCorrectly() {
        String template = "Result: {value}";
        Map<String, Object> context = Map.of("value", "$100 & <tag>");

        String result = parser.render(template, context);

        assertThat(result).isEqualTo("Result: $100 & <tag>");
    }
}
