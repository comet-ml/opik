package com.comet.opik.domain.evaluation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extractor must feed {@link com.comet.opik.domain.cost.CostService} for every provider opik
 * prices. Base prompt/completion/total tokens come from the generic {@link TokenUsage} interface, so
 * they are covered for any provider (including the ones that return the base type — Vertex AI and the
 * OpenAI-compatible OpenRouter/Ollama/custom-llm/free). The provider-specific subtypes additionally
 * carry prompt-cache tokens.
 */
class LlmUsageExtractorTest {

    private static ChatResponse responseWith(TokenUsage usage) {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(usage).build();
    }

    @Test
    void nullUsageReturnsNull() {
        assertThat(LlmUsageExtractor.toUsageMap(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build())).isNull();
    }

    @Test
    void baseTokenUsageIsCapturedForAnyProvider() {
        // The base type is what Vertex AI and OpenAI-compatible providers return — no cache subtype.
        // total (130) deliberately differs from prompt+completion (120) so the assertion verifies the
        // provider-reported total is passed through, not recomputed from the parts.
        var usage = LlmUsageExtractor.toUsageMap(responseWith(new TokenUsage(100, 20, 130)));

        assertThat(usage)
                .containsEntry("prompt_tokens", 100)
                .containsEntry("completion_tokens", 20)
                .containsEntry("total_tokens", 130)
                .doesNotContainKeys("cache_creation_input_tokens", "cache_read_input_tokens");
    }

    @Test
    void anthropicCacheTokensAreCaptured() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(AnthropicTokenUsage.builder()
                .inputTokenCount(100).outputTokenCount(20)
                .cacheCreationInputTokens(30).cacheReadInputTokens(40).build()));

        assertThat(usage)
                .containsEntry("prompt_tokens", 100)
                .containsEntry("completion_tokens", 20)
                .containsEntry("cache_creation_input_tokens", 30)
                .containsEntry("cache_read_input_tokens", 40);
    }

    @Test
    void openAiCacheTokensAreCaptured() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(OpenAiTokenUsage.builder()
                .inputTokenCount(200).outputTokenCount(50).totalTokenCount(250)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(75).build())
                .build()));

        assertThat(usage)
                .containsEntry("prompt_tokens", 200)
                .containsEntry("cache_read_input_tokens", 75);
    }

    @Test
    void openAiOfficialResponsesApiCacheTokensAreCaptured() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(OpenAiOfficialTokenUsage.builder()
                .inputTokenCount(300).outputTokenCount(60).totalTokenCount(360)
                .inputTokensDetails(OpenAiOfficialTokenUsage.InputTokensDetails.builder().cachedTokens(90).build())
                .build()));

        assertThat(usage)
                .containsEntry("prompt_tokens", 300)
                .containsEntry("cache_read_input_tokens", 90);
    }

    @Test
    void geminiCachedContentTokensAreCaptured() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(400).outputTokenCount(70).cachedContentTokenCount(120).build()));

        assertThat(usage)
                .containsEntry("prompt_tokens", 400)
                .containsEntry("cache_read_input_tokens", 120);
    }

    // The value != null && value > 0 guard is what keeps the usage map (and therefore cost) unchanged
    // when a provider reports no caching. Each branch it protects is exercised independently below so a
    // regression in one is reported by name and never masked by an earlier failure.

    @Test
    void omitsCacheKeysWhenAnthropicCacheFieldsAreNull() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(AnthropicTokenUsage.builder()
                .inputTokenCount(100).outputTokenCount(20).build()));
        assertThat(usage)
                .containsEntry("prompt_tokens", 100)
                .doesNotContainKeys("cache_creation_input_tokens", "cache_read_input_tokens");
    }

    @Test
    void omitsCacheKeysWhenAnthropicCacheFieldsAreZero() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(AnthropicTokenUsage.builder()
                .inputTokenCount(100).outputTokenCount(20)
                .cacheCreationInputTokens(0).cacheReadInputTokens(0).build()));
        assertThat(usage)
                .doesNotContainKeys("cache_creation_input_tokens", "cache_read_input_tokens");
    }

    @Test
    void omitsCacheKeyWhenOpenAiInputDetailsAreNull() {
        var usage = LlmUsageExtractor.toUsageMap(responseWith(OpenAiTokenUsage.builder()
                .inputTokenCount(200).outputTokenCount(50).totalTokenCount(250).build()));
        assertThat(usage).doesNotContainKey("cache_read_input_tokens");
    }
}
