package com.comet.opik.domain.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MustacheParser Test")
class MustacheParserTest {

    private MustacheParser mustacheParser;

    @BeforeEach
    void setUp() {
        mustacheParser = new MustacheParser();
    }

    @Test
    @DisplayName("should extract variables from valid template")
    void shouldExtractVariablesFromValidTemplate() {
        String template = "Hello {{name}}, you are {{age}} years old and live in {{city}}.";

        Set<String> variables = mustacheParser.extractVariables(template);

        assertThat(variables).containsExactlyInAnyOrder("name", "age", "city");
    }

    @Test
    @DisplayName("should return empty set for template without variables")
    void shouldReturnEmptySetForTemplateWithoutVariables() {
        String template = "Hello world, this is a simple text without variables.";

        Set<String> variables = mustacheParser.extractVariables(template);

        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should handle malformed template with unclosed tag")
    void shouldHandleMalformedTemplateWithUnclosedTag() {
        String malformedTemplate = "Hello {{name}}, this is a malformed template with {{unclosed";

        Set<String> variables = mustacheParser.extractVariables(malformedTemplate);

        // Should return empty set instead of throwing exception
        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should handle malformed template with unclosed mustache tag")
    void shouldHandleMalformedTemplateWithUnclosedMustacheTag() {
        String malformedTemplate = "Hello {{name}}, this is a malformed template with {{#section}} but no closing";

        Set<String> variables = mustacheParser.extractVariables(malformedTemplate);

        // Should return empty set instead of throwing exception
        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should render valid template with context")
    void shouldRenderValidTemplateWithContext() {
        String template = "Hello {{name}}, you are {{age}} years old.";
        Map<String, Object> context = Map.of("name", "John", "age", 30);

        String result = mustacheParser.render(template, context);

        assertThat(result).isEqualTo("Hello John, you are 30 years old.");
    }

    @Test
    @DisplayName("should throw exception when rendering malformed template")
    void shouldThrowExceptionWhenRenderingMalformedTemplate() {
        String malformedTemplate = "Hello {{name}}, this is a malformed template with {{unclosed";
        Map<String, Object> context = Map.of("name", "John");

        assertThatThrownBy(() -> mustacheParser.render(malformedTemplate, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Mustache template");
    }

    @Test
    @DisplayName("should handle null template")
    void shouldHandleNullTemplate() {
        Set<String> variables = mustacheParser.extractVariables(null);

        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("should handle empty template")
    void shouldHandleEmptyTemplate() {
        Set<String> variables = mustacheParser.extractVariables("");

        assertThat(variables).isEmpty();
    }

    @Nested
    @DisplayName("renderUnescaped")
    class RenderUnescaped {

        @Test
        @DisplayName("should not HTML-escape special characters")
        void shouldNotHtmlEscapeSpecialCharacters() {
            String template = "Content: {{value}}";
            Map<String, Object> context = Map.of("value", "<b>bold</b> & 'quoted' \"text\"");

            String result = mustacheParser.renderUnescaped(template, context);

            assertThat(result).isEqualTo("Content: <b>bold</b> & 'quoted' \"text\"");
        }

        @Test
        @DisplayName("should render normally for values without special characters")
        void shouldRenderNormallyForPlainValues() {
            String template = "Hello {{name}}, you are {{age}} years old.";
            Map<String, Object> context = Map.of("name", "Alice", "age", 25);

            String result = mustacheParser.renderUnescaped(template, context);

            assertThat(result).isEqualTo("Hello Alice, you are 25 years old.");
        }

        @Test
        @DisplayName("should return empty string for null template")
        void shouldReturnEmptyStringForNullTemplate() {
            String result = mustacheParser.renderUnescaped(null, Map.of());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should throw on malformed template")
        void shouldThrowOnMalformedTemplate() {
            Map<String, Object> context = Map.of("name", "test");

            assertThatThrownBy(() -> mustacheParser.renderUnescaped(
                    "Hello {{name}} {{#section}} unclosed", context))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid Mustache template");
        }

        @Test
        @DisplayName("default render should HTML-escape while renderUnescaped should not")
        void shouldDifferFromDefaultRender() {
            String template = "Value: {{val}}";
            Map<String, Object> context = Map.of("val", "<script>alert('xss')</script>");

            String escaped = mustacheParser.render(template, context);
            String unescaped = mustacheParser.renderUnescaped(template, context);

            // Default render escapes HTML
            assertThat(escaped).contains("&lt;script&gt;");
            assertThat(escaped).doesNotContain("<script>");

            // Unescaped render preserves HTML as-is
            assertThat(unescaped).contains("<script>alert('xss')</script>");
            assertThat(unescaped).doesNotContain("&lt;");
        }
    }
}