package com.comet.opik.domain.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCapabilitiesTest {

    @Test
    void supportsVisionReturnsTrueForKnownVisionModels() {
        assertThat(ModelCapabilities.supportsVision("gpt-4-vision-preview")).isTrue();
        assertThat(ModelCapabilities.supportsVision("gpt-4o")).isTrue();
        assertThat(ModelCapabilities.supportsVision("claude-3-5-sonnet-20241022")).isTrue();
        assertThat(ModelCapabilities.supportsVision("gemini-1.5-pro")).isTrue();
    }

    @Test
    void supportsVisionReturnsFalseForNonVisionModels() {
        assertThat(ModelCapabilities.supportsVision("gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("gpt-4")).isFalse();
    }

    @Test
    void supportsVisionReturnsFalseForUnknownModels() {
        assertThat(ModelCapabilities.supportsVision("unknown-model-12345")).isFalse();
    }

    @Test
    void supportsVisionReturnsFalseForBlankModelName() {
        assertThat(ModelCapabilities.supportsVision("")).isFalse();
        assertThat(ModelCapabilities.supportsVision("   ")).isFalse();
        assertThat(ModelCapabilities.supportsVision(null)).isFalse();
    }

    @Test
    void supportsVisionMatchesModelWithDifferentProviderPrefix() {
        // Test that "qwen/qwen2.5-vl-32b-instruct" matches "deepinfra/Qwen/Qwen2.5-VL-32B-Instruct"
        // The matching logic should find models even with different provider prefixes
        assertThat(ModelCapabilities.supportsVision("qwen/qwen2.5-vl-32b-instruct")).isTrue();
        assertThat(ModelCapabilities.supportsVision("deepinfra/Qwen/Qwen2.5-VL-32B-Instruct")).isTrue();
    }

    @Test
    void supportsVisionIsCaseInsensitive() {
        assertThat(ModelCapabilities.supportsVision("GPT-4-VISION-PREVIEW")).isTrue();
        assertThat(ModelCapabilities.supportsVision("Gpt-4o")).isTrue();
        assertThat(ModelCapabilities.supportsVision("CLAUDE-3-5-SONNET-20241022")).isTrue();
    }

    @Test
    void supportsVisionMatchesModelWithoutProviderPrefix() {
        // If stored as "provider/model", it should also match just "model"
        assertThat(ModelCapabilities.supportsVision("gpt-4o")).isTrue();
        assertThat(ModelCapabilities.supportsVision("openai/gpt-4o")).isTrue();
    }

    @Test
    void supportsVisionHandlesWhitespace() {
        assertThat(ModelCapabilities.supportsVision("  gpt-4o  ")).isTrue();
        assertThat(ModelCapabilities.supportsVision("\tgpt-4o\n")).isTrue();
    }

    @Test
    void supportsVisionMatchesWithColonSuffix() {
        // Model with colon suffix should still match base model if it supports vision
        assertThat(ModelCapabilities.supportsVision("gpt-4o:free")).isTrue();
        assertThat(ModelCapabilities.supportsVision("gpt-4-vision-preview:extended")).isTrue();
    }

    @Test
    void supportsVisionMatchesViaRegexPattern() {
        // Models matching vision patterns should return true
        // The pattern ".*qwen.*vl.*" matches qwen vision models
        // These models are in the JSON and should be detected as vision-capable
        assertThat(ModelCapabilities.supportsVision("deepinfra/Qwen/Qwen2.5-VL-32B-Instruct")).isTrue();
        assertThat(ModelCapabilities.supportsVision("openrouter/qwen/qwen-vl-plus")).isTrue();
        assertThat(ModelCapabilities.supportsVision("ovhcloud/Qwen2.5-VL-72B-Instruct")).isTrue();
    }

    @Test
    void supportsVisionWorksWithMultiplePrefixVariations() {
        // Test that the same model works with different provider prefixes
        var modelVariations = List.of(
                "qwen2.5-vl-32b-instruct",
                "qwen/qwen2.5-vl-32b-instruct",
                "deepinfra/qwen/qwen2.5-vl-32b-instruct",
                "openrouter/qwen/qwen2.5-vl-32b-instruct");

        for (var variation : modelVariations) {
            assertThat(ModelCapabilities.supportsVision(variation))
                    .as("Model variation '%s' should support vision", variation)
                    .isTrue();
        }
    }

    @Test
    void supportsVisionReturnsFalseForNonVisionModelVariations() {
        // Even with different prefixes, non-vision models should return false
        assertThat(ModelCapabilities.supportsVision("gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("openai/gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("GPT-3.5-TURBO")).isFalse();
    }
}
