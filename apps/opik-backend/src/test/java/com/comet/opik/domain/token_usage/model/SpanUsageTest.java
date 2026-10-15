package com.comet.opik.domain.token_usage.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;

class SpanUsageTest {

    private static final NativeUsage ANTHROPIC = AnthropicNativeUsage.builder().provider("anthropic")
            .variant("anthropic_native").inputTokens(200).cacheReadInputTokens(30).outputTokens(100).build();

    @Test
    void toUsageMapMovesRawKeysUnderOriginalUsageNextToTheTotals() {
        var spanUsage = withNativeUsage(OriginalUsage.from(
                usage("input_tokens", 200, "output_tokens", 100, "cache_read_input_tokens", 30)), ANTHROPIC);

        assertThat(spanUsage.toUsageMap()).isEqualTo(usage("original_usage.input_tokens", 200,
                "original_usage.output_tokens", 100, "original_usage.cache_read_input_tokens", 30,
                "prompt_tokens", 230, "completion_tokens", 100, "total_tokens", 330));
    }

    @Test
    void toUsageMapKeepsConvertedUsageAndReplacesItsTotals() {
        var spanUsage = withNativeUsage(OriginalUsage.from(usage("prompt_tokens", 1, "completion_tokens", 2,
                "total_tokens", 3, "requests", 2, "original_usage.input_tokens", 200,
                "original_usage.output_tokens", 100, "original_usage.cache_read_input_tokens", 30)), ANTHROPIC);

        assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 230, "completion_tokens", 100,
                "total_tokens", 330, "requests", 2, "original_usage.input_tokens", 200,
                "original_usage.output_tokens", 100, "original_usage.cache_read_input_tokens", 30));
    }

    @Test
    void toUsageMapKeepsTotalsOnlyUsageAtTheTopLevel() {
        var nativeUsage = OpenAiNativeUsage.builder().provider("mistral").variant("chat_completions")
                .promptTokens(10).completionTokens(20).totalTokens(30).build();
        var spanUsage = withNativeUsage(OriginalUsage.from(usage("prompt_tokens", 10, "completion_tokens", 20,
                "total_tokens", 30)), nativeUsage);

        assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 10, "completion_tokens", 20,
                "total_tokens", 30));
    }

    @Test
    void toUsageMapWithoutNativeUsageKeepsUsageAsSentWithTheKnownTotals() {
        var original = OriginalUsage.from(usage("prompt_tokens", 20, "characters", 1200));
        var spanUsage = new SpanUsage(original, null, new OpenAiLikeUsage(20L, null, null, null), List.of());

        assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 20, "characters", 1200));
    }

    @Test
    void toUsageMapWithoutNativeUsageKeepsConvertedUsageAsSent() {
        var usage = usage("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30,
                "original_usage.characters", 1200);
        var spanUsage = new SpanUsage(OriginalUsage.from(usage), null, new OpenAiLikeUsage(20L, 10L, 30L, null),
                List.of());

        assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
    }

    @Test
    void toUsageMapOfEmptyUsageIsEmpty() {
        var spanUsage = new SpanUsage(OriginalUsage.EMPTY, null, OpenAiLikeUsage.EMPTY, List.of());

        assertThat(spanUsage.toUsageMap()).isEmpty();
    }

    @Test
    void toUsageMapCapsTotalsAtTheLargestStorableCount() {
        var nativeUsage = AnthropicNativeUsage.builder().provider("anthropic").variant("anthropic_native")
                .inputTokens(Integer.MAX_VALUE).cacheReadInputTokens(Integer.MAX_VALUE).outputTokens(1).build();
        var spanUsage = withNativeUsage(OriginalUsage.from(usage("input_tokens", Integer.MAX_VALUE,
                "cache_read_input_tokens", Integer.MAX_VALUE, "output_tokens", 1)), nativeUsage);

        assertThat(spanUsage.toUsageMap()).containsEntry("prompt_tokens", Integer.MAX_VALUE)
                .containsEntry("completion_tokens", 1)
                .containsEntry("total_tokens", Integer.MAX_VALUE);
    }

    @Test
    void toUsageMapIsImmutable() {
        var spanUsage = withNativeUsage(OriginalUsage.from(usage("input_tokens", 1)), ANTHROPIC);

        assertThat(spanUsage.toUsageMap()).isUnmodifiable();
    }

    @Test
    void toUsageMapNeverWritesTheCachedTokens() {
        var spanUsage = withNativeUsage(OriginalUsage.from(usage("input_tokens", 200, "cache_read_input_tokens", 30)),
                ANTHROPIC);

        assertThat(spanUsage.toUsageMap()).doesNotContainKeys("cached_tokens", "cached_prompt_tokens")
                .containsOnlyKeys("original_usage.input_tokens", "original_usage.cache_read_input_tokens",
                        "prompt_tokens", "completion_tokens", "total_tokens");
    }

    private static SpanUsage withNativeUsage(OriginalUsage original, NativeUsage nativeUsage) {
        return new SpanUsage(original, nativeUsage, nativeUsage.toOpenAiLike(), List.of());
    }
}
