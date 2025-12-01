package com.comet.opik.domain.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCapabilitiesTest {

    @Test
    void supportsVisionHandlesDotNotation_issue4114() {
        // Test for issue #4114: Model names with dots should be normalized to hyphens
        // This ensures vision capability detection works consistently with cost calculation

        // Claude models with dots should match their hyphenated equivalents
        assertThat(ModelCapabilities.supportsVision("claude-3.5-sonnet-20241022")).isTrue();
        assertThat(ModelCapabilities.supportsVision("claude-3-5-sonnet-20241022")).isTrue(); // Both should work

        // Gemini models with dots
        assertThat(ModelCapabilities.supportsVision("gemini-1.5-pro")).isTrue();
        assertThat(ModelCapabilities.supportsVision("gemini-1-5-pro")).isTrue(); // Both should work

        // Qwen models with dots (also matches vision pattern)
        assertThat(ModelCapabilities.supportsVision("qwen2.5-vl-32b-instruct")).isTrue();
        assertThat(ModelCapabilities.supportsVision("qwen2-5-vl-32b-instruct")).isTrue(); // Both should work
    }

    /**
     * Parameterized test for vision support across various model names.
     * Consolidates existing tests and adds new cases for issue #4114.
     */
    @ParameterizedTest
    @MethodSource("provideModelNamesForVisionSupport")
    void supportsVision_shouldReturnCorrectResult(String modelName, boolean expectedResult, String description) {
        assertThat(ModelCapabilities.supportsVision(modelName))
                .as(description)
                .isEqualTo(expectedResult);
    }

    private static Stream<Arguments> provideModelNamesForVisionSupport() {
        return Stream.of(
                // Known vision models
                Arguments.of("gpt-4-vision-preview", true, "GPT-4 Vision Preview supports vision"),
                Arguments.of("gpt-4o", true, "GPT-4o supports vision"),
                Arguments.of("claude-3-5-sonnet-20241022", true, "Claude 3.5 Sonnet supports vision"),
                Arguments.of("gemini-1.5-pro", true, "Gemini 1.5 Pro supports vision"),

                // Non-vision models
                Arguments.of("gpt-3.5-turbo", false, "GPT-3.5 Turbo does not support vision"),
                Arguments.of("gpt-4", false, "GPT-4 (base) does not support vision"),

                // Unknown models
                Arguments.of("unknown-model-12345", false, "Unknown model should return false"),

                // Blank/null models
                Arguments.of("", false, "Empty string should return false"),
                Arguments.of("   ", false, "Whitespace only should return false"),
                Arguments.of(null, false, "Null should return false"),

                // Vision pattern matches
                Arguments.of("qwen/qwen2.5-vl-32b-instruct", true, "Qwen VL model supports vision"),
                Arguments.of("deepinfra/Qwen/Qwen2.5-VL-32B-Instruct", true, "Deepinfra Qwen VL supports vision"),
                Arguments.of("openrouter/qwen/qwen-2.5-coder-32b-instruct", false,
                        "Qwen Coder does not support vision"),

                // Case insensitivity
                Arguments.of("GPT-4-VISION-PREVIEW", true, "Uppercase vision model should work"),
                Arguments.of("Gpt-4o", true, "Mixed case vision model should work"),
                Arguments.of("CLAUDE-3-5-SONNET-20241022", true, "Uppercase Claude should work"),

                // Provider prefix handling
                Arguments.of("openai/gpt-4o", true, "Model with provider prefix should work"),

                // Whitespace handling
                Arguments.of("  gpt-4o  ", true, "Model with leading/trailing spaces should work"),
                Arguments.of("\tgpt-4o\n", true, "Model with tabs/newlines should work"),

                // Colon suffixes
                Arguments.of("qwen/qwen2.5-vl-32b-instruct:free", true, "Model with :free suffix should work"),
                Arguments.of("gpt-4o:free", true, "GPT-4o with :free suffix should work"),
                Arguments.of("gpt-3.5-turbo:free", false, "Non-vision model with :free suffix should not work"),

                // Non-vision model variations
                Arguments.of("openai/gpt-3.5-turbo", false, "Non-vision with provider prefix should not work"),
                Arguments.of("GPT-3.5-TURBO", false, "Uppercase non-vision should not work"),

                // Dot notation (issue #4114)
                Arguments.of("claude-3.5-sonnet-20241022", true, "Claude with dots should work"),
                Arguments.of("gemini-1-5-pro", true, "Gemini without dots should work"),
                Arguments.of("qwen2.5-vl-32b-instruct", true, "Qwen VL with dots should work"),
                Arguments.of("qwen2-5-vl-32b-instruct", true, "Qwen VL without dots should work"));
    }
}