package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.BedrockConverseNativeUsage;
import com.comet.opik.domain.token_usage.model.GeminiNativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiLikeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Optional;

@UtilityClass
class SpanCostCalculator {
    private static final String VIDEO_DURATION_KEY = "video_duration_seconds";
    private static final String INPUT_CHARACTERS_KEY = "input_characters";

    /**
     * Prices the provider's own usage record, so every token bucket gets its own rate whatever client logged it. Usage
     * no format could read is priced from its totals.
     */
    public static BigDecimal textGenerationCost(@NonNull ModelPrice modelPrice, @NonNull SpanUsage usage) {
        return switch (usage.nativeUsage()) {
            case AnthropicNativeUsage anthropic -> anthropicCost(modelPrice, anthropic);
            case BedrockConverseNativeUsage bedrock -> bedrockCost(modelPrice, bedrock);
            case GeminiNativeUsage gemini -> geminiCost(modelPrice, gemini);
            case OpenAiNativeUsage openAi -> openAiCost(modelPrice, openAi);
            case null -> totalsCost(modelPrice, usage.openAiLike());
        };
    }

    private static BigDecimal anthropicCost(ModelPrice modelPrice, AnthropicNativeUsage usage) {
        int tierBasis = tierBasis(usage.toOpenAiLike().promptTokens());
        BigDecimal inputPrice = modelPrice.effectiveInputPrice(tierBasis);
        BigDecimal fiveMinuteWritePrice = orInputPrice(
                modelPrice.effectiveCacheCreationInputTokenPrice(tierBasis), inputPrice);
        BigDecimal oneHourWritePrice = orInputPrice(modelPrice.cacheCreationInputTokenPriceAbove1hr(),
                fiveMinuteWritePrice);
        return cost(inputPrice, usage.inputTokens())
                .add(cost(orInputPrice(modelPrice.effectiveCacheReadInputTokenPrice(tierBasis), inputPrice),
                        usage.cacheReadInputTokens()))
                .add(cost(fiveMinuteWritePrice, usage.cacheCreation5mInputTokens()))
                .add(cost(oneHourWritePrice, usage.cacheCreation1hInputTokens()))
                .add(cost(modelPrice.effectiveOutputPrice(tierBasis), usage.outputTokens()));
    }

    private static BigDecimal bedrockCost(ModelPrice modelPrice, BedrockConverseNativeUsage usage) {
        int tierBasis = tierBasis(usage.toOpenAiLike().promptTokens());
        BigDecimal inputPrice = modelPrice.effectiveInputPrice(tierBasis);
        return cost(inputPrice, usage.inputTokens())
                .add(cost(orInputPrice(modelPrice.effectiveCacheReadInputTokenPrice(tierBasis), inputPrice),
                        usage.cacheReadInputTokens()))
                .add(cost(orInputPrice(modelPrice.effectiveCacheCreationInputTokenPrice(tierBasis), inputPrice),
                        usage.cacheWriteInputTokens()))
                .add(cost(modelPrice.effectiveOutputPrice(tierBasis), usage.outputTokens()));
    }

    private static BigDecimal geminiCost(ModelPrice modelPrice, GeminiNativeUsage usage) {
        long promptTokens = usage.toOpenAiLike().promptTokens();
        int tierBasis = tierBasis(promptTokens);
        BigDecimal inputPrice = modelPrice.effectiveInputPrice(tierBasis);
        BigDecimal outputPrice = modelPrice.effectiveOutputPrice(tierBasis);
        long cachedTokens = Math.min(usage.cachedContentTokenCount(), promptTokens);
        return cost(inputPrice, promptTokens - cachedTokens)
                .add(cost(orInputPrice(modelPrice.effectiveCacheReadInputTokenPrice(tierBasis), inputPrice),
                        cachedTokens))
                .add(cost(outputPrice, usage.candidatesTokenCount()))
                .add(cost(orInputPrice(modelPrice.outputReasoningTokenPrice(), outputPrice),
                        usage.thoughtsTokenCount()));
    }

    private static BigDecimal openAiCost(ModelPrice modelPrice, OpenAiNativeUsage usage) {
        int tierBasis = tierBasis(usage.promptTokens());
        BigDecimal inputPrice = modelPrice.effectiveInputPrice(tierBasis);
        BigDecimal outputPrice = modelPrice.effectiveOutputPrice(tierBasis);

        // Audio and reasoning are subsets of the prompt and completion, split out only when they have a rate of their
        // own; otherwise they bill as text.
        long audioPromptTokens = isPositive(modelPrice.inputAudioTokenPrice()) ? usage.audioPromptTokens() : 0;
        long textPromptTokens = Optional.ofNullable(usage.textPromptTokens())
                .orElse(Math.max(0,
                        usage.promptTokens() - usage.cachedTokens() - usage.cacheWriteTokens() - audioPromptTokens));
        long audioCompletionTokens = isPositive(modelPrice.outputAudioTokenPrice())
                ? usage.audioCompletionTokens()
                : 0;
        long textCompletionTokens = Optional.ofNullable(usage.textCompletionTokens())
                .orElse(Math.max(0, usage.completionTokens() - audioCompletionTokens));
        long reasoningTokens = isPositive(modelPrice.outputReasoningTokenPrice())
                ? Math.clamp(usage.reasoningTokens(), 0, textCompletionTokens)
                : 0;

        return cost(inputPrice, textPromptTokens)
                .add(cost(modelPrice.inputAudioTokenPrice(), audioPromptTokens))
                .add(cost(orInputPrice(modelPrice.effectiveCacheReadInputTokenPrice(tierBasis), inputPrice),
                        usage.cachedTokens()))
                .add(cost(orInputPrice(modelPrice.effectiveCacheCreationInputTokenPrice(tierBasis), inputPrice),
                        usage.cacheWriteTokens()))
                .add(cost(outputPrice, textCompletionTokens - reasoningTokens))
                .add(cost(modelPrice.outputAudioTokenPrice(), audioCompletionTokens))
                .add(cost(modelPrice.outputReasoningTokenPrice(), reasoningTokens));
    }

    private static BigDecimal totalsCost(ModelPrice modelPrice, OpenAiLikeUsage usage) {
        long promptTokens = Optional.ofNullable(usage.promptTokens()).orElse(0L);
        int tierBasis = tierBasis(promptTokens);
        return cost(modelPrice.effectiveInputPrice(tierBasis), promptTokens)
                .add(cost(modelPrice.effectiveOutputPrice(tierBasis),
                        Optional.ofNullable(usage.completionTokens()).orElse(0L)));
    }

    public static BigDecimal defaultCost(@NonNull ModelPrice modelPrice, @NonNull SpanUsage usage) {
        return BigDecimal.ZERO;
    }

    public static BigDecimal audioSpeechCost(@NonNull ModelPrice modelPrice, @NonNull SpanUsage usage) {
        long inputCharacters = originalCount(usage, INPUT_CHARACTERS_KEY);
        BigDecimal characterPrice = modelPrice.audioInputCharacterPrice();
        if (inputCharacters <= 0 || !isPositive(characterPrice)) {
            return BigDecimal.ZERO;
        }
        return cost(characterPrice, inputCharacters);
    }

    public static BigDecimal videoGenerationCost(@NonNull ModelPrice modelPrice, @NonNull SpanUsage usage) {
        long durationSeconds = originalCount(usage, VIDEO_DURATION_KEY);
        BigDecimal videoPrice = modelPrice.videoOutputPrice();
        if (durationSeconds <= 0 || !isPositive(videoPrice)) {
            return BigDecimal.ZERO;
        }
        return cost(videoPrice, durationSeconds);
    }

    private static long originalCount(SpanUsage usage, String name) {
        return Optional.ofNullable(usage.original().keys().get(name))
                .or(() -> Optional.ofNullable(usage.original().outerKeys().get(name)))
                .orElse(0);
    }

    // A provider that publishes no cache rate bills its cached tokens as plain input.
    private static BigDecimal orInputPrice(BigDecimal price, BigDecimal fallbackPrice) {
        return isPositive(price) ? price : fallbackPrice;
    }

    // The whole prompt, cache included, decides which above_NNNk tier applies.
    private static int tierBasis(long promptTokens) {
        return (int) Math.min(promptTokens, Integer.MAX_VALUE);
    }

    private static BigDecimal cost(BigDecimal price, long tokens) {
        return price.multiply(BigDecimal.valueOf(tokens));
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
