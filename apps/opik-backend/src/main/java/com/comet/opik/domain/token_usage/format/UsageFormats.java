package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.NativeUsage;
import com.comet.opik.domain.token_usage.model.OriginalUsage;
import com.comet.opik.domain.token_usage.model.UsageWarning;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The official usage formats, picked by provider. Providers are LiteLLM provider names, as {@code ProviderRegistry}
 * returns them. Providers without a format of their own get OpenAI's, since most serve OpenAI-compatible APIs.
 */
@UtilityClass
public class UsageFormats {

    static final UsageFormat ANTHROPIC = new AnthropicUsageFormat();
    static final UsageFormat OPENAI = new OpenAiUsageFormat();
    static final UsageFormat GEMINI = new GeminiUsageFormat();
    static final UsageFormat BEDROCK_CONVERSE = new BedrockConverseUsageFormat();

    static final List<UsageFormat> ALL = List.of(ANTHROPIC, OPENAI, GEMINI, BEDROCK_CONVERSE);

    private static final Map<String, UsageFormat> FORMAT_BY_PROVIDER = Map.of(
            "anthropic", ANTHROPIC,
            "vertex_ai-anthropic_models", ANTHROPIC,
            "gemini", GEMINI,
            "vertex_ai-language-models", GEMINI,
            // LiteLLM's own provider for Vertex calls, where Gemini is the native model family.
            "vertex_ai", GEMINI,
            "bedrock", BEDROCK_CONVERSE,
            "bedrock_converse", BEDROCK_CONVERSE);

    /**
     * The provider's native usage, from the first input variant of its format that matches the usage, or empty when
     * none does.
     */
    public Optional<NativeUsage> normalize(@NonNull String provider, @NonNull OriginalUsage original,
            @NonNull List<UsageWarning> warnings) {
        return forProvider(provider).normalize(provider, original, warnings);
    }

    UsageFormat forProvider(@NonNull String provider) {
        return FORMAT_BY_PROVIDER.getOrDefault(provider, OPENAI);
    }

    static Set<String> providersWithOwnFormat() {
        return FORMAT_BY_PROVIDER.keySet();
    }
}
