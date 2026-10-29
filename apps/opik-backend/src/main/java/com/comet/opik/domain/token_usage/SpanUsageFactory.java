package com.comet.opik.domain.token_usage;

import com.comet.opik.domain.token_usage.format.UsageFormats;
import com.comet.opik.domain.token_usage.model.OpenAiLikeUsage;
import com.comet.opik.domain.token_usage.model.OriginalUsage;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.comet.opik.domain.token_usage.model.UsageWarning;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.token_usage.model.OpenAiLikeUsage.COMPLETION_TOKENS;
import static com.comet.opik.domain.token_usage.model.OpenAiLikeUsage.PROMPT_TOKENS;
import static com.comet.opik.domain.token_usage.model.OpenAiLikeUsage.TOTAL_TOKENS;

/**
 * Builds a {@link SpanUsage} from a span's usage map. The provider picks its official format, and the first of that
 * format's input variants that matches the usage fills the native record. The provider is never inferred from the usage.
 * Pure and stateless: it doesn't log or emit metrics, it returns warnings for the caller to count.
 */
@UtilityClass
public class SpanUsageFactory {

    /**
     * @param provider a LiteLLM provider name, as {@code ProviderRegistry} resolves it, or {@code null} when the span
     *                 has none.
     */
    public SpanUsage create(@Nullable String provider, @Nullable Map<String, Integer> usage) {
        var original = OriginalUsage.from(usage);
        if (StringUtils.isBlank(provider) || original.keys().isEmpty()) {
            return withoutNativeUsage(original, List.of());
        }
        var warnings = new ArrayList<UsageWarning>();
        var nativeUsage = UsageFormats.normalize(provider, original, warnings);
        if (nativeUsage.isEmpty()) {
            return withoutNativeUsage(original,
                    List.of(UsageWarning.noMatchingVariant(provider, original.keys().keySet())));
        }
        return new SpanUsage(original, nativeUsage.get(), nativeUsage.get().toOpenAiLike(), List.copyOf(warnings));
    }

    /**
     * Without a native record, the totals are the client's own. A missing total is the sum when both others were sent,
     * as the SDK does for usage it doesn't recognize.
     */
    private SpanUsage withoutNativeUsage(OriginalUsage original, List<UsageWarning> warnings) {
        var clientKeys = original.nested() ? original.outerKeys() : original.keys();
        Long promptTokens = asLong(clientKeys.get(PROMPT_TOKENS));
        Long completionTokens = asLong(clientKeys.get(COMPLETION_TOKENS));
        Long totalTokens = asLong(clientKeys.get(TOTAL_TOKENS));
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        return new SpanUsage(original, null, new OpenAiLikeUsage(promptTokens, completionTokens, totalTokens, null),
                warnings);
    }

    private Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
