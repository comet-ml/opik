package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.OriginalUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;

class UsageKeysTest {

    private static UsageKeys keys(Object... keysAndValues) {
        return UsageKeys.of(OriginalUsage.from(usage(keysAndValues)));
    }

    @Nested
    class Normalize {

        @ParameterizedTest
        @CsvSource({
                "input_tokens, input_tokens",
                "inputTokens, input_tokens",
                "InputTokens, input_tokens",
                "INPUT_TOKENS, input_tokens",
                "input-tokens, input_tokens",
                "Input-Tokens, input_tokens",
                "cacheReadInputTokens, cache_read_input_tokens",
                "promptTokenCount, prompt_token_count",
                "promptTokensDetails.cachedTokens, prompt_tokens_details.cached_tokens",
                "cache_creation.ephemeral_5m_input_tokens, cache_creation.ephemeral_5m_input_tokens",
                "iterations.0.inputTokens, iterations.0.input_tokens",
                "gen_ai.usage.input_tokens, input_tokens",
                "gen_ai.usage.cache_read.input_tokens, cache_read.input_tokens",
                "GEN_AI.USAGE.INPUT_TOKENS, input_tokens",
                "llm.token_count.prompt, prompt",
                "llm.token_count.prompt_details.cache_read, prompt_details.cache_read"})
        void normalize(String key, String expected) {
            assertThat(UsageKeys.normalize(key)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"vendor.input_tokens", "usage.input_tokens", "cache_read.input_tokens",
                "gen_ai.input_tokens"})
        void normalizeKeepsAQualifierThatIsNotANamespace(String key) {
            assertThat(UsageKeys.normalize(key)).isEqualTo(key);
        }

        @ParameterizedTest
        @ValueSource(strings = {"gen_ai.usage.", "llm.token_count."})
        void normalizeKeepsANamespaceWithoutName(String key) {
            assertThat(UsageKeys.normalize(key)).isEqualTo(key);
        }

        @Test
        void normalizeStripsOnlyTheFirstNamespace() {
            assertThat(UsageKeys.normalize("gen_ai.usage.llm.token_count.prompt")).isEqualTo("llm.token_count.prompt");
        }
    }

    @Nested
    class Lookup {

        @Test
        void hasMatchesAnySpelling() {
            var keys = keys("gen_ai.usage.cacheReadInputTokens", 5);

            assertThat(keys.has("cache_read_input_tokens")).isTrue();
            assertThat(keys.has("cacheReadInputTokens")).isFalse();
            assertThat(keys.has("input_tokens")).isFalse();
        }

        @Test
        void hasCountsAKeySentAsZero() {
            assertThat(keys("input_tokens", 0).has("input_tokens")).isTrue();
        }

        @Test
        void hasAny() {
            var keys = keys("output_tokens", 1);

            assertThat(keys.hasAny("input_tokens", "output_tokens")).isTrue();
            assertThat(keys.hasAny("input_tokens", "prompt_tokens")).isFalse();
            assertThat(keys.hasAny()).isFalse();
        }

        @Test
        void getReturnsZeroForAMissingKey() {
            var keys = keys("input_tokens", 7);

            assertThat(keys.get("input_tokens")).isEqualTo(7);
            assertThat(keys.get("output_tokens")).isZero();
        }

        @Test
        void firstTakesTheFirstPresentKeyEvenWhenItIsZero() {
            var keys = keys("cache_read_input_tokens", 0, "cached_tokens", 30);

            assertThat(keys.first("prompt_tokens_details.cached_tokens", "cache_read_input_tokens", "cached_tokens"))
                    .isZero();
            assertThat(keys.first("prompt_tokens_details.cached_tokens", "cached_tokens")).isEqualTo(30);
            assertThat(keys.first("missing")).isZero();
        }

        @Test
        void ofPicksTheSameSpellingWhateverTheOrderItWasSentIn() {
            var forward = new LinkedHashMap<String, Integer>();
            forward.put("inputTokens", 1);
            forward.put("input_tokens", 2);
            var backward = new LinkedHashMap<String, Integer>();
            backward.put("input_tokens", 2);
            backward.put("inputTokens", 1);

            long fromForward = UsageKeys.of(OriginalUsage.from(forward)).get("input_tokens");
            long fromBackward = UsageKeys.of(OriginalUsage.from(backward)).get("input_tokens");

            assertThat(fromForward).isEqualTo(fromBackward).isEqualTo(1);
        }

        @Test
        void ofReadsOnlyTheProviderKeysOfConvertedUsage() {
            var keys = UsageKeys
                    .of(OriginalUsage.from(usage("prompt_tokens", 280, "original_usage.input_tokens", 200)));

            assertThat(keys.has("input_tokens")).isTrue();
            assertThat(keys.has("prompt_tokens")).isFalse();
        }
    }

    @Nested
    class ListItems {

        @Test
        void hasListItems() {
            assertThat(keys("iterations.0.input_tokens", 5).hasListItems("iterations")).isTrue();
            assertThat(keys("iterations.12.output_tokens", 5).hasListItems("iterations")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"iterations.input_tokens", "iterations.first.input_tokens", "iterations.0",
                "iterations", "iterations..input_tokens", "iterationsx.0.input_tokens", "input_tokens"})
        void hasListItemsIgnoresKeysThatAreNotIndexedItems(String key) {
            assertThat(keys(key, 5).hasListItems("iterations")).isFalse();
        }

        @Test
        void sumOverListItemsAddsTheFieldOfEveryItem() {
            var keys = keys("input_tokens", 23000,
                    "iterations.0.input_tokens", 180000, "iterations.0.output_tokens", 3500,
                    "iterations.1.input_tokens", 23000, "iterations.1.output_tokens", 1000,
                    "iterations.2.output_tokens", 7);

            assertThat(keys.sumOverListItems("iterations", "input_tokens")).isEqualTo(203000);
            assertThat(keys.sumOverListItems("iterations", "output_tokens")).isEqualTo(4507);
        }

        @Test
        void sumOverListItemsMatchesTheWholeFieldName() {
            var keys = keys("iterations.0.cache_creation_input_tokens", 10,
                    "iterations.0.cache_creation.ephemeral_5m_input_tokens", 4,
                    "iterations.0.input_tokens", 1);

            assertThat(keys.sumOverListItems("iterations", "cache_creation_input_tokens")).isEqualTo(10);
            assertThat(keys.sumOverListItems("iterations", "cache_creation.ephemeral_5m_input_tokens")).isEqualTo(4);
            assertThat(keys.sumOverListItems("iterations", "missing")).isZero();
        }

        @Test
        void sumOverListItemsMatchesAnySpelling() {
            var keys = keys("iterations.0.inputTokens", 3, "iterations.1.input_tokens", 4);

            assertThat(keys.sumOverListItems("iterations", "input_tokens")).isEqualTo(7);
        }
    }
}
