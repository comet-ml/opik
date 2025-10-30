package com.comet.opik.domain.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelNameMatcherTest {

    // ========== normalize() Tests ==========

    @Test
    void normalizeReturnsEmptyStringForNull() {
        assertThat(ModelNameMatcher.normalize(null)).isEmpty();
    }

    @Test
    void normalizeReturnsEmptyStringForBlankString() {
        assertThat(ModelNameMatcher.normalize("")).isEmpty();
        assertThat(ModelNameMatcher.normalize("   ")).isEmpty();
        assertThat(ModelNameMatcher.normalize("\t\n")).isEmpty();
    }

    @Test
    void normalizeTrimsWhitespace() {
        assertThat(ModelNameMatcher.normalize("  gpt-4  ")).isEqualTo("gpt-4");
        assertThat(ModelNameMatcher.normalize("\tgpt-4\n")).isEqualTo("gpt-4");
    }

    @Test
    void normalizeConvertsToLowercase() {
        assertThat(ModelNameMatcher.normalize("GPT-4")).isEqualTo("gpt-4");
        assertThat(ModelNameMatcher.normalize("OpenAI/GPT-4")).isEqualTo("openai/gpt-4");
        assertThat(ModelNameMatcher.normalize("DeepInfra/Qwen/Model")).isEqualTo("deepinfra/qwen/model");
    }

    @Test
    void normalizeHandlesMixedCaseAndWhitespace() {
        assertThat(ModelNameMatcher.normalize("  OpenAI/GPT-4  ")).isEqualTo("openai/gpt-4");
    }

    // ========== generateCandidateKeys() Tests ==========

    @Test
    void generateCandidateKeysReturnsEmptyListForNull() {
        assertThat(ModelNameMatcher.generateCandidateKeys(null)).isEmpty();
    }

    @Test
    void generateCandidateKeysReturnsEmptyListForBlankString() {
        assertThat(ModelNameMatcher.generateCandidateKeys("")).isEmpty();
        assertThat(ModelNameMatcher.generateCandidateKeys("   ")).isEmpty();
    }

    @Test
    void generateCandidateKeysForSimpleModelName() {
        var candidates = ModelNameMatcher.generateCandidateKeys("gpt-4");

        assertThat(candidates).containsExactly("gpt-4");
    }

    @Test
    void generateCandidateKeysForModelWithOneSlash() {
        var candidates = ModelNameMatcher.generateCandidateKeys("openai/gpt-4");

        assertThat(candidates).containsExactly(
                "openai/gpt-4",
                "gpt-4");
    }

    @Test
    void generateCandidateKeysForModelWithMultipleSlashes() {
        var candidates = ModelNameMatcher.generateCandidateKeys("deepinfra/qwen/qwen2.5-vl-32b-instruct");

        assertThat(candidates).containsExactly(
                "deepinfra/qwen/qwen2.5-vl-32b-instruct",
                "qwen/qwen2.5-vl-32b-instruct",
                "qwen2.5-vl-32b-instruct");
    }

    @Test
    void generateCandidateKeysForModelWithColon() {
        var candidates = ModelNameMatcher.generateCandidateKeys("model:free");

        assertThat(candidates).containsExactly(
                "model:free",
                "model");
    }

    @Test
    void generateCandidateKeysForModelWithSlashAndColon() {
        var candidates = ModelNameMatcher.generateCandidateKeys("openrouter/model:free");

        assertThat(candidates).containsExactly(
                "openrouter/model:free",
                "model:free",
                "openrouter/model",
                "model");
    }

    @Test
    void generateCandidateKeysForComplexModelWithMultipleSlashesAndColon() {
        var candidates = ModelNameMatcher.generateCandidateKeys("openrouter/qwen/model:free");

        assertThat(candidates).containsExactly(
                "openrouter/qwen/model:free",
                "qwen/model:free",
                "model:free",
                "openrouter/qwen/model",
                "qwen/model",
                "model");
    }

    @Test
    void generateCandidateKeysNormalizesCase() {
        var candidates = ModelNameMatcher.generateCandidateKeys("OpenRouter/Qwen/Model:Free");

        assertThat(candidates).containsExactly(
                "openrouter/qwen/model:free",
                "qwen/model:free",
                "model:free",
                "openrouter/qwen/model",
                "qwen/model",
                "model");
    }

    @Test
    void generateCandidateKeysTrimsWhitespace() {
        var candidates = ModelNameMatcher.generateCandidateKeys("  openai/gpt-4  ");

        assertThat(candidates).containsExactly(
                "openai/gpt-4",
                "gpt-4");
    }

    @Test
    void generateCandidateKeysPreventsDuplicates() {
        // If normalization creates duplicates, they should be removed
        var candidates = ModelNameMatcher.generateCandidateKeys("model/model");

        assertThat(candidates).containsExactly(
                "model/model",
                "model");
    }

    @Test
    void generateCandidateKeysHandlesMultipleColons() {
        var candidates = ModelNameMatcher.generateCandidateKeys("model:free:v2");

        // Should only strip from the first colon
        assertThat(candidates).containsExactly(
                "model:free:v2",
                "model");
    }

    // ========== findInMap() Tests ==========

    @Test
    void findInMapReturnsEmptyForNullModelName() {
        var map = Map.of("gpt-4", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, null, index)).isEmpty();
    }

    @Test
    void findInMapReturnsEmptyForBlankModelName() {
        var map = Map.of("gpt-4", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, "", index)).isEmpty();
        assertThat(ModelNameMatcher.findInMap(map, "   ", index)).isEmpty();
    }

    @Test
    void findInMapReturnsEmptyForEmptyMap() {
        var map = Map.<String, String>of();
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, "gpt-4", index)).isEmpty();
    }

    @Test
    void findInMapFindsExactMatch() {
        var map = Map.of(
                "gpt-4", "value1",
                "gpt-3.5-turbo", "value2");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, "gpt-4", index)).contains("value1");
        assertThat(ModelNameMatcher.findInMap(map, "gpt-3.5-turbo", index)).contains("value2");
    }

    @Test
    void findInMapIsCaseInsensitive() {
        var map = Map.of("gpt-4", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, "GPT-4", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "Gpt-4", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "GPT-4", index)).contains("value");
    }

    @Test
    void findInMapTrimsWhitespace() {
        var map = Map.of("gpt-4", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, "  gpt-4  ", index)).contains("value");
    }

    @Test
    void findInMapMatchesWithoutProviderPrefix() {
        var map = Map.of("openai/gpt-4", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // Should match both with and without prefix
        assertThat(ModelNameMatcher.findInMap(map, "openai/gpt-4", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "gpt-4", index)).contains("value");
    }

    @Test
    void findInMapMatchesSuffixWithDifferentPrefix() {
        var map = Map.of("deepinfra/qwen/qwen2.5-vl-32b-instruct", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // All these should match via suffix matching
        assertThat(ModelNameMatcher.findInMap(map, "deepinfra/qwen/qwen2.5-vl-32b-instruct", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "qwen/qwen2.5-vl-32b-instruct", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "qwen2.5-vl-32b-instruct", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "openrouter/qwen/qwen2.5-vl-32b-instruct", index)).contains("value");
    }

    @Test
    void findInMapHandlesColonSuffixes() {
        var map = Map.of("openrouter/model", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // "model:free" should match "openrouter/model" after stripping colon
        assertThat(ModelNameMatcher.findInMap(map, "model:free", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "openrouter/model:free", index)).contains("value");
    }

    @Test
    void findInMapReturnsEmptyForNoMatch() {
        var map = Map.of("gpt-4", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        assertThat(ModelNameMatcher.findInMap(map, "unknown-model", index)).isEmpty();
    }

    @Test
    void findInMapPrioritizesExactMatchOverSuffixMatch() {
        var map = Map.of(
                "deepinfra/qwen/model", "deepinfra-value",
                "openrouter/qwen/model", "openrouter-value",
                "qwen/model", "exact-value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // Should return exact match, not suffix match
        assertThat(ModelNameMatcher.findInMap(map, "qwen/model", index)).contains("exact-value");
    }

    @Test
    void findInMapHandlesComplexScenario() {
        var map = Map.of(
                "openai/gpt-4", "openai-value",
                "anthropic/claude-3", "anthropic-value",
                "deepinfra/qwen/qwen2.5-vl-32b-instruct", "deepinfra-value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // Various matching scenarios
        assertThat(ModelNameMatcher.findInMap(map, "gpt-4", index)).contains("openai-value");
        assertThat(ModelNameMatcher.findInMap(map, "CLAUDE-3", index)).contains("anthropic-value");
        assertThat(ModelNameMatcher.findInMap(map, "qwen/qwen2.5-vl-32b-instruct", index)).contains("deepinfra-value");
        assertThat(ModelNameMatcher.findInMap(map, "qwen2.5-vl-32b-instruct", index)).contains("deepinfra-value");
    }

    @Test
    void findInMapReturnsFirstMatchInSuffixMatching() {
        var map = Map.of(
                "provider1/model", "value1",
                "provider2/model", "value2");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // When searching for "model", should return one of them (iteration order dependent)
        var result = ModelNameMatcher.findInMap(map, "model", index);
        assertThat(result).isPresent();
        assertThat(result.get()).isIn("value1", "value2");
    }

    @Test
    void findInMapHandlesCaseInsensitiveSuffixMatching() {
        var map = Map.of("deepinfra/qwen/model", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // Case-insensitive suffix matching
        assertThat(ModelNameMatcher.findInMap(map, "DeepInfra/Qwen/Model", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "QWEN/MODEL", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "Model", index)).contains("value");
    }

    @Test
    void findInMapMatchesCompleteSegments() {
        var map = Map.of("openai/gpt-4-turbo", "value");
        var index = ModelNameMatcher.buildSuffixIndex(map);

        // These should all match because they are complete segments
        assertThat(ModelNameMatcher.findInMap(map, "gpt-4-turbo", index)).contains("value");
        assertThat(ModelNameMatcher.findInMap(map, "openai/gpt-4-turbo", index)).contains("value");

        // "turbo" alone won't match because "gpt-4-turbo" is a single segment
        // (no slash separating "gpt-4" from "turbo")
        assertThat(ModelNameMatcher.findInMap(map, "turbo", index)).isEmpty();
    }
}
