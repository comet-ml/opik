package com.comet.opik.domain.token_usage.model;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * One span's usage in three forms, read to price it. The span keeps its usage as the client sent it.
 *
 * @param original    as logged, never modified.
 * @param nativeUsage the provider's official record, or {@code null} when there's no provider or no input variant
 *                    of its format matched.
 * @param openAiLike  the prompt, completion and total tokens, which price usage without a native record.
 * @param warnings    usage that didn't match its format's conventions.
 */
@Builder(toBuilder = true)
public record SpanUsage(
        @NonNull OriginalUsage original,
        @Nullable NativeUsage nativeUsage,
        @NonNull OpenAiLikeUsage openAiLike,
        @NonNull List<UsageWarning> warnings) {
}
