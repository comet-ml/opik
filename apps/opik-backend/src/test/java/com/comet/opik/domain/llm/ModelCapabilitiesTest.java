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
    void supportsVisionMatchesExplicitlyMappedEnumValues() {
        // Test that explicitly mapped OpenRouter enum "qwen/qwen2.5-vl-32b-instruct"
        // correctly resolves to its canonical JSON key "deepinfra/Qwen/Qwen2.5-VL-32B-Instruct"
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
    void supportsVisionMatchesViaRegexPattern() {
        // Models matching vision patterns should return true
        // The pattern ".*qwen.*vl.*" matches qwen vision models
        // These models are in the JSON and should be detected as vision-capable
        assertThat(ModelCapabilities.supportsVision("deepinfra/Qwen/Qwen2.5-VL-32B-Instruct")).isTrue();
        assertThat(ModelCapabilities.supportsVision("openrouter/qwen/qwen-vl-plus")).isTrue();
        assertThat(ModelCapabilities.supportsVision("ovhcloud/Qwen2.5-VL-72B-Instruct")).isTrue();
    }

    @Test
    void supportsVisionRequiresExplicitMappingsOrRegexMatch() {
        // With explicit enum mapping approach, these are supported:

        // ✓ Explicitly mapped OpenRouter enum value
        assertThat(ModelCapabilities.supportsVision("qwen/qwen2.5-vl-32b-instruct")).isTrue();

        // ✓ Direct JSON key (exact match)
        assertThat(ModelCapabilities.supportsVision("deepinfra/Qwen/Qwen2.5-VL-32B-Instruct")).isTrue();

        // ✓ Matches regex pattern ".*qwen.*vl.*" (fallback for unmapped Qwen VL models)
        assertThat(ModelCapabilities.supportsVision("qwen2.5-vl-32b-instruct")).isTrue();

        // ✗ Non-vision models without explicit mappings or regex matches are NOT supported
        assertThat(ModelCapabilities.supportsVision("qwen-2.5-72b-instruct")).isFalse(); // No "vl" in name
        assertThat(ModelCapabilities.supportsVision("random-model")).isFalse();
    }

    @Test
    void supportsVisionReturnsFalseForNonVisionModelVariations() {
        // Even with different prefixes, non-vision models should return false
        assertThat(ModelCapabilities.supportsVision("gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("openai/gpt-3.5-turbo")).isFalse();
        assertThat(ModelCapabilities.supportsVision("GPT-3.5-TURBO")).isFalse();
    }
}
