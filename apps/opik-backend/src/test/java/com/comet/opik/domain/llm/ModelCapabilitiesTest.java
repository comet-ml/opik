package com.comet.opik.domain.llm;

import org.junit.jupiter.api.Test;

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
    void supportsVisionReturnsTrueForModelsThatMatchVisionPattern() {
        // Should be true because it matches the vision pattern
        assertThat(ModelCapabilities.supportsVision("qwen/qwen2.5-vl-32b-instruct")).isTrue();
        assertThat(ModelCapabilities.supportsVision("deepinfra/Qwen/Qwen2.5-VL-32B-Instruct")).isTrue();
        // Should be false because it doesn't match the vision pattern
        assertThat(ModelCapabilities.supportsVision("openrouter/qwen/qwen-2.5-coder-32b-instruct")).isFalse();
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
    void supportsVisionHandlesColonSuffixes() {
        // Colon suffixes are automatically stripped and matched to base model

        // ✓ "qwen/qwen2.5-vl-32b-instruct:free" has explicit mapping
        assertThat(ModelCapabilities.supportsVision("qwen/qwen2.5-vl-32b-instruct:free")).isTrue();

        // ✓ "gpt-4o:free" falls back to "gpt-4o" (which exists in JSON and supports vision)
        assertThat(ModelCapabilities.supportsVision("gpt-4o:free")).isTrue();

        // ✗ "gpt-3.5-turbo:free" falls back to "gpt-3.5-turbo" (exists but doesn't support vision)
        assertThat(ModelCapabilities.supportsVision("gpt-3.5-turbo:free")).isFalse();
    }

    @Test
    void supportsVisionReturnsFalseForNonVisionModelVariations() {
        // Even with different prefixes, non-vision models should return false
        assertThat(ModelCapabilities.supportsVision("gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("openai/gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("GPT-3.5-TURBO")).isFalse();
    }
}