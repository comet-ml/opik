package com.comet.opik.domain.token_usage.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.HashMap;
import java.util.Map;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class OriginalUsageTest {

    @ParameterizedTest
    @NullAndEmptySource
    void fromNothingIsEmpty(Map<String, Integer> usage) {
        assertThat(OriginalUsage.from(usage)).isEqualTo(OriginalUsage.EMPTY);
    }

    @Test
    void fromRawUsageKeepsEveryKey() {
        var usage = usage("input_tokens", 200, "output_tokens", 100, "cache_creation.ephemeral_5m_input_tokens", 50);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(usage, Map.of(), false));
    }

    @Test
    void fromConvertedUsageSplitsTheProviderKeysFromTheOuterKeys() {
        var usage = usage("prompt_tokens", 280, "completion_tokens", 100, "total_tokens", 380,
                "original_usage.input_tokens", 200, "original_usage.output_tokens", 100,
                "original_usage.cache_read_input_tokens", 80);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(
                usage("input_tokens", 200, "output_tokens", 100, "cache_read_input_tokens", 80),
                usage("prompt_tokens", 280, "completion_tokens", 100, "total_tokens", 380),
                true));
    }

    @Test
    void fromConvertedUsageWithoutOuterKeysIsNested() {
        var usage = usage("original_usage.input_tokens", 7);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(usage("input_tokens", 7), Map.of(), true));
    }

    @Test
    void fromUsageConvertedTwiceKeepsOnlyTheDeepestLevel() {
        var usage = usage("prompt_tokens", 230, "completion_tokens", 100,
                "original_usage.prompt_tokens", 230, "original_usage.completion_tokens", 100,
                "original_usage.original_usage.input_tokens", 200,
                "original_usage.original_usage.output_tokens", 100);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(
                usage("input_tokens", 200, "output_tokens", 100),
                usage("prompt_tokens", 230, "completion_tokens", 100),
                true));
    }

    @Test
    void fromSkipsNullKeysAndNullCounts() {
        var usage = new HashMap<String, Integer>();
        usage.put(null, 5);
        usage.put("input_tokens", null);
        usage.put("output_tokens", 3);
        usage.put("original_usage.cache_read_input_tokens", null);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(usage("output_tokens", 3), Map.of(),
                false));
    }

    @Test
    void fromSkipsAPrefixWithoutName() {
        var usage = usage("original_usage.", 5, "original_usage.original_usage.", 6, "output_tokens", 3);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(usage("output_tokens", 3), Map.of(),
                false));
    }

    @Test
    void fromKeepsAPrefixThatIsNotAtTheStart() {
        var usage = usage("vendor.original_usage.input_tokens", 5);

        assertThat(OriginalUsage.from(usage)).isEqualTo(new OriginalUsage(usage, Map.of(), false));
    }

    @Test
    void fromKeepsZeroAndNegativeCountsAsSent() {
        var usage = usage("input_tokens", 0, "output_tokens", -1);

        assertThat(OriginalUsage.from(usage).keys()).isEqualTo(usage);
    }

    @Test
    void fromSortsTheKeysAndReturnsImmutableMaps() {
        var original = OriginalUsage.from(usage("b", 2, "original_usage.z", 26, "a", 1, "original_usage.y", 25));

        assertThat(original.keys()).containsExactly(Map.entry("y", 25), Map.entry("z", 26)).isUnmodifiable();
        assertThat(original.outerKeys()).containsExactly(Map.entry("a", 1), Map.entry("b", 2)).isUnmodifiable();
    }

    @Test
    void fromDoesNotKeepAReferenceToTheUsage() {
        var usage = new HashMap<>(usage("input_tokens", 1));

        var original = OriginalUsage.from(usage);
        usage.put("output_tokens", 2);

        assertThat(original.keys()).isEqualTo(Map.of("input_tokens", 1));
    }

    @Test
    void fromReadsTheDeepestLevelOfAKeyWithManyPrefixes() {
        var original = OriginalUsage.from(usage(OriginalUsage.ORIGINAL_USAGE_PREFIX.repeat(3_000) + "input_tokens", 5));

        assertThat(original.keys()).isEqualTo(usage("input_tokens", 5));
        assertThat(original.nested()).isTrue();
    }

    @Test
    void constructionCopiesTheMapsItIsGiven() {
        var keys = new HashMap<>(usage("output_tokens", 2, "input_tokens", 1));
        var outerKeys = new HashMap<>(usage("total_tokens", 3));

        var original = OriginalUsage.builder().keys(keys).outerKeys(outerKeys).nested(true).build();
        keys.put("cache_read_input_tokens", 7);
        outerKeys.clear();

        assertThat(original.keys()).containsExactly(entry("input_tokens", 1), entry("output_tokens", 2));
        assertThat(original.outerKeys()).isEqualTo(usage("total_tokens", 3));
        assertThat(original.keys()).isUnmodifiable();
    }
}
