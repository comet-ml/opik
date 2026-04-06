package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.uuid.Generators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for eval suite assertion evaluation logic in EvalSuiteAssertionSampler.
 * The SDK's LLMJudge uses Python-style {variable} templates, but the backend's
 * OnlineScoringEngine uses Mustache {{variable}} templates. This test verifies
 * the conversion and substitution pipeline.
 */
class EvalSuiteAssertionSamplerTest {

    // SDK-style template (single braces) - as stored in evaluator config
    private static final String SDK_USER_TEMPLATE = """
            ## Input
            ---BEGIN INPUT---
            {input}
            ---END INPUT---

            ## Output
            ---BEGIN OUTPUT---
            {output}
            ---END OUTPUT---

            ## Assertions
            {assertions}
            """;

    @Nested
    @DisplayName("Assertions text generation")
    class AssertionsTextGeneration {

        @Test
        @DisplayName("builds assertions text from schema matching SDK format")
        void buildAssertionsTextFromSchema() {
            var schema = List.of(
                    LlmAsJudgeOutputSchema.builder()
                            .name("no_toc")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("The report must not include a table of contents.")
                            .build(),
                    LlmAsJudgeOutputSchema.builder()
                            .name("has_summary")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("The report must include a summary section.")
                            .build());

            String assertionsText = buildAssertionsText(schema);

            assertThat(assertionsText).isEqualTo(
                    "- `assertion_1`: The report must not include a table of contents.\n"
                            + "- `assertion_2`: The report must include a summary section.");
        }

        @Test
        @DisplayName("renames schema items to assertion_N to align with assertions text keys")
        void schemaItemsRenamedToAssertionKeys() {
            var schema = List.of(
                    LlmAsJudgeOutputSchema.builder()
                            .name("The report must not include a table of contents.")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("The report must not include a table of contents.")
                            .build(),
                    LlmAsJudgeOutputSchema.builder()
                            .name("The report must include a summary section.")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("The report must include a summary section.")
                            .build());

            var renamedSchema = renameSchemaToAssertionKeys(schema);

            assertThat(renamedSchema).hasSize(2);
            assertThat(renamedSchema.get(0).name()).isEqualTo("assertion_1");
            assertThat(renamedSchema.get(0).description())
                    .isEqualTo("The report must not include a table of contents.");
            assertThat(renamedSchema.get(1).name()).isEqualTo("assertion_2");
            assertThat(renamedSchema.get(1).description()).isEqualTo("The report must include a summary section.");
        }

        @Test
        @DisplayName("handles single assertion")
        void singleAssertion() {
            var schema = List.of(
                    LlmAsJudgeOutputSchema.builder()
                            .name("check")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Output is correct.")
                            .build());

            String assertionsText = buildAssertionsText(schema);

            assertThat(assertionsText).isEqualTo("- `assertion_1`: Output is correct.");
        }
    }

    @Nested
    @DisplayName("Template format conversion")
    class TemplateFormatConversion {

        @Test
        @DisplayName("SDK single-brace placeholders are NOT valid Mustache - left as-is")
        void singleBracePlaceholdersNotSubstitutedByMustache() {
            String sdkTemplate = "Hello {input}, your output is {output}";
            Map<String, String> replacements = Map.of("input", "world", "output", "42");

            String rendered = TemplateParseUtils.render(sdkTemplate, replacements, PromptType.MUSTACHE);

            // Single braces are NOT substituted by Mustache
            assertThat(rendered).isEqualTo(sdkTemplate);
        }

        @Test
        @DisplayName("Mustache double-brace placeholders ARE properly substituted")
        void doubleBracePlaceholdersSubstitutedByMustache() {
            String mustacheTemplate = "Hello {{input}}, your output is {{output}}";
            Map<String, String> replacements = Map.of("input", "world", "output", "42");

            String rendered = TemplateParseUtils.render(mustacheTemplate, replacements, PromptType.MUSTACHE);

            assertThat(rendered).isEqualTo("Hello world, your output is 42");
        }

        @Test
        @DisplayName("converts single braces to triple braces for variable patterns")
        void convertSingleBracesToTripleBraces() {
            String converted = convertSdkTemplateToMustache("{input} and {output} and {assertions}");

            assertThat(converted).isEqualTo("{{{input}}} and {{{output}}} and {{{assertions}}}");
        }

        @Test
        @DisplayName("does not convert already-mustache double-brace templates")
        void doesNotConvertDoubleBraces() {
            String alreadyMustache = "{{input}} and {{output}}";
            String converted = convertSdkTemplateToMustache(alreadyMustache);

            assertThat(converted).isEqualTo(alreadyMustache);
        }

        @Test
        @DisplayName("preserves JSON-like braces that don't match variable pattern")
        void preservesJsonBraces() {
            String template = "{\"key\": \"value\"} and {input}";
            String converted = convertSdkTemplateToMustache(template);

            assertThat(converted).contains("{{{input}}}");
            assertThat(converted).contains("{\"key\"");
        }

        @Test
        @DisplayName("converted SDK template can be rendered by Mustache")
        void convertedTemplateRendersCorrectly() {
            String converted = convertSdkTemplateToMustache("Result: {input} - {output}");
            Map<String, String> replacements = Map.of("input", "hello", "output", "world");

            String rendered = TemplateParseUtils.render(converted, replacements, PromptType.MUSTACHE);

            assertThat(rendered).isEqualTo("Result: hello - world");
        }
    }

    @Nested
    @DisplayName("End-to-end variable substitution")
    class EndToEndSubstitution {

        @Test
        @DisplayName("OnlineScoringEngine.toReplacements resolves literal assertions from variables map")
        void toReplacementsHandlesLiteralAssertions() {
            String assertionsText = "- `assertion_1`: Check this.";
            var variables = Map.of(
                    "input", "input",
                    "output", "output",
                    "assertions", assertionsText);

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .build();

            Map<String, String> replacements = OnlineScoringEngine.toReplacements(variables, trace);

            assertThat(replacements).containsKey("input");
            assertThat(replacements).containsKey("output");
            // assertions is a literal value (not a trace section path)
            assertThat(replacements).containsEntry("assertions", assertionsText);
        }

        @Test
        @DisplayName("full pipeline: convert template + inject assertions + render produces correct output")
        void fullPipelineProducesCorrectOutput() {
            var schema = List.of(
                    LlmAsJudgeOutputSchema.builder()
                            .name("no_toc")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("No table of contents.")
                            .build(),
                    LlmAsJudgeOutputSchema.builder()
                            .name("has_tldr")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Has TL;DR section.")
                            .build());

            // Step 1: Build assertions text from schema
            String assertionsText = buildAssertionsText(schema);

            // Step 2: Convert SDK template to Mustache format
            String mustacheTemplate = convertSdkTemplateToMustache(
                    "Input: {input}, Output: {output}, Assertions: {assertions}");

            // Step 3: Provide replacements (simulating what toReplacements produces)
            Map<String, String> replacements = Map.of(
                    "input", "user question",
                    "output", "ai answer",
                    "assertions", assertionsText);

            // Step 4: Render
            String rendered = TemplateParseUtils.render(mustacheTemplate, replacements, PromptType.MUSTACHE);

            assertThat(rendered).isEqualTo(
                    "Input: user question, Output: ai answer, Assertions: "
                            + "- `assertion_1`: No table of contents.\n"
                            + "- `assertion_2`: Has TL;DR section.");
        }
    }

    // Helper methods matching the logic that should be in EvalSuiteAssertionSampler

    private static List<LlmAsJudgeOutputSchema> renameSchemaToAssertionKeys(List<LlmAsJudgeOutputSchema> schema) {
        return java.util.stream.IntStream.range(0, schema.size())
                .mapToObj(i -> schema.get(i).toBuilder()
                        .name("assertion_%d".formatted(i + 1))
                        .build())
                .toList();
    }

    private static String buildAssertionsText(List<LlmAsJudgeOutputSchema> schema) {
        return java.util.stream.IntStream.range(0, schema.size())
                .mapToObj(i -> "- `assertion_%d`: %s".formatted(i + 1, schema.get(i).description()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * Converts SDK-style single-brace templates {var} to Mustache triple-brace {{{var}}}.
     * Triple braces prevent Mustache from HTML-escaping the substituted values,
     * which is needed because assertions text contains backticks and newlines.
     * Only converts patterns matching variable names (word characters), not JSON braces.
     */
    static String convertSdkTemplateToMustache(String template) {
        // Match {word_chars} that are NOT preceded by { and not followed by }
        return template.replaceAll("(?<!\\{)\\{(\\w+)}(?!})", "{{{$1}}}");
    }
}
