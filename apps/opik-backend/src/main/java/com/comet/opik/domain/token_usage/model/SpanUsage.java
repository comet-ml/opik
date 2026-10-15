package com.comet.opik.domain.token_usage.model;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.comet.opik.domain.token_usage.model.OpenAiLikeUsage.COMPLETION_TOKENS;
import static com.comet.opik.domain.token_usage.model.OpenAiLikeUsage.PROMPT_TOKENS;
import static com.comet.opik.domain.token_usage.model.OpenAiLikeUsage.TOTAL_TOKENS;
import static com.comet.opik.domain.token_usage.model.OriginalUsage.ORIGINAL_USAGE_PREFIX;

/**
 * One span's usage in three forms, the backend's equivalent of the SDK's {@code OpikUsage}.
 *
 * @param original    as logged, never modified.
 * @param nativeUsage the provider's official record, or {@code null} when there's no provider or no input variant
 *                    of its format matched.
 * @param openAiLike  what the frontend, filters and trace totals read.
 * @param warnings    for the caller to count in metrics.
 */
@Builder(toBuilder = true)
public record SpanUsage(
        @NonNull OriginalUsage original,
        @Nullable NativeUsage nativeUsage,
        @NonNull OpenAiLikeUsage openAiLike,
        @NonNull List<UsageWarning> warnings) {

    private static final Set<String> TOTAL_KEYS = Set.of(PROMPT_TOKENS, COMPLETION_TOKENS, TOTAL_TOKENS);

    /**
     * The usage map to store. It keeps today's shape, so it's a no-op on what the SDK already sends when the totals
     * agree: the provider's usage under {@code original_usage.*}, and the known totals at the top level. Building a
     * {@code SpanUsage} from this map again gives the same result.
     */
    public Map<String, Integer> toUsageMap() {
        var usage = new TreeMap<String, Integer>();
        if (original.nested()) {
            usage.putAll(original.outerKeys());
            original.keys().forEach((name, value) -> usage.put(ORIGINAL_USAGE_PREFIX + name, value));
        } else if (nativeUsage == null || TOTAL_KEYS.containsAll(original.keys().keySet())) {
            // Nothing to derive, or only the totals were sent: stored as sent.
            usage.putAll(original.keys());
        } else {
            // Raw provider keys move under original_usage.* once, so the derived totals don't collide with them.
            original.keys().forEach((name, value) -> usage.put(ORIGINAL_USAGE_PREFIX + name, value));
        }
        putIfKnown(usage, PROMPT_TOKENS, openAiLike.promptTokens());
        putIfKnown(usage, COMPLETION_TOKENS, openAiLike.completionTokens());
        putIfKnown(usage, TOTAL_TOKENS, openAiLike.totalTokens());
        return Collections.unmodifiableMap(usage);
    }

    private static void putIfKnown(Map<String, Integer> usage, String name, @Nullable Long count) {
        if (count != null) {
            // Every ingestion boundary types token counts as Integer, so a derived count only exceeds it on corrupt input.
            usage.put(name, (int) Math.min(count, Integer.MAX_VALUE));
        }
    }
}
