package com.comet.opik.domain.token_usage;

import com.comet.opik.domain.token_usage.model.OpenAiLikeUsage;
import com.comet.opik.domain.token_usage.model.UsageContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Agreement with the Python SDK's {@code opik.llm_usage} while both compute the totals: the same raw usage gives the
 * SDK's totals, and what the SDK sends after converting it is stored unchanged. The numbers are the SDK's own test
 * expectations; nested objects are flattened the way the SDK flattens them.
 */
class SdkParityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void createGivesTheSdkTotals(String description, String provider, Map<String, Integer> usage,
            long promptTokens, long completionTokens, long totalTokens) {
        var openAiLike = SpanUsageFactory.create(provider, usage, UsageContext.NONE).openAiLike();

        assertThat(openAiLike).usingRecursiveComparison().ignoringFields("cachedPromptTokens")
                .isEqualTo(new OpenAiLikeUsage(promptTokens, completionTokens, totalTokens, null));
    }

    static Stream<Arguments> createGivesTheSdkTotals() {
        return Stream.of(
                arguments("OpenAI Chat Completions", "openai",
                        usage("completion_tokens", 100, "prompt_tokens", 200, "total_tokens", 300,
                                "completion_tokens_details.accepted_prediction_tokens", 50,
                                "completion_tokens_details.audio_tokens", 20, "prompt_tokens_details.audio_tokens", 10,
                                "prompt_tokens_details.cached_tokens", 30, "video_seconds", 10),
                        200, 100, 300),
                arguments("OpenAI totals only", "openai",
                        usage("completion_tokens", 10, "prompt_tokens", 20, "total_tokens", 30), 20, 10, 30),
                arguments("Anthropic without cache", "anthropic",
                        usage("input_tokens", 10, "output_tokens", 20), 10, 20, 30),
                arguments("Anthropic with cache read and write", "anthropic",
                        usage("input_tokens", 200, "output_tokens", 100, "cache_creation_input_tokens", 50,
                                "cache_read_input_tokens", 30),
                        280, 100, 380),
                arguments("Anthropic, the same call as the Bedrock one", "anthropic",
                        usage("input_tokens", 12, "output_tokens", 7, "cache_creation_input_tokens", 1024,
                                "cache_read_input_tokens", 4096),
                        5132, 7, 5139),
                arguments("Anthropic compaction iterations", "anthropic",
                        usage("input_tokens", 23000, "output_tokens", 1000, "cache_creation_input_tokens", 0,
                                "cache_read_input_tokens", 0,
                                "iterations.0.input_tokens", 180000, "iterations.0.output_tokens", 3500,
                                "iterations.0.cache_creation_input_tokens", 0, "iterations.0.cache_read_input_tokens",
                                0,
                                "iterations.1.input_tokens", 23000, "iterations.1.output_tokens", 1000,
                                "iterations.1.cache_creation_input_tokens", 0, "iterations.1.cache_read_input_tokens",
                                0),
                        203000, 4500, 207500),
                arguments("Anthropic compaction iterations with caching", "anthropic",
                        usage("input_tokens", 23000, "output_tokens", 1000, "cache_creation_input_tokens", 500,
                                "cache_read_input_tokens", 5000,
                                "iterations.0.input_tokens", 180000, "iterations.0.output_tokens", 3500,
                                "iterations.0.cache_read_input_tokens", 10000,
                                "iterations.0.cache_creation_input_tokens", 2000,
                                "iterations.1.input_tokens", 23000, "iterations.1.output_tokens", 1000,
                                "iterations.1.cache_read_input_tokens", 5000,
                                "iterations.1.cache_creation_input_tokens", 500),
                        220500, 4500, 225000),
                arguments("Bedrock Converse with cache", "bedrock",
                        usage("inputTokens", 200, "outputTokens", 100, "cacheWriteInputTokens", 50,
                                "cacheReadInputTokens", 30),
                        280, 100, 380),
                arguments("Bedrock Converse, the same call as the Anthropic one", "bedrock",
                        usage("inputTokens", 12, "outputTokens", 7, "cacheReadInputTokens", 4096,
                                "cacheWriteInputTokens", 1024),
                        5132, 7, 5139),
                arguments("Bedrock Converse without cache", "bedrock",
                        usage("inputTokens", 200, "outputTokens", 100, "totalTokens", 300), 200, 100, 300),
                arguments("Bedrock Converse with cache counters at zero", "bedrock",
                        usage("inputTokens", 200, "outputTokens", 100, "totalTokens", 300, "cacheReadInputTokens", 0,
                                "cacheWriteInputTokens", 0),
                        200, 100, 300),
                arguments("Gemini with cached content", "gemini",
                        usage("candidates_token_count", 100, "prompt_token_count", 200, "total_token_count", 300,
                                "cached_content_token_count", 50),
                        200, 100, 300),
                arguments("Gemini without candidates", "gemini",
                        usage("prompt_token_count", 10, "total_token_count", 10), 10, 0, 10),
                arguments("Gemini on Vertex without candidates", "vertex_ai-language-models",
                        usage("prompt_token_count", 7859, "total_token_count", 7859), 7859, 0, 7859),
                arguments("Gemini tool use with thoughts", "gemini",
                        usage("prompt_token_count", 27, "candidates_token_count", 45, "thoughts_token_count", 31,
                                "tool_use_prompt_token_count", 10309, "total_token_count", 10412),
                        27 + 10309, 45 + 31, 10412),
                arguments("Gemini tool use without thoughts", "gemini",
                        usage("prompt_token_count", 1041, "candidates_token_count", 902,
                                "tool_use_prompt_token_count", 377545, "total_token_count", 379488),
                        1041 + 377545, 902, 379488),
                arguments("Gemini plain", "gemini",
                        usage("prompt_token_count", 200, "candidates_token_count", 100, "total_token_count", 300),
                        200, 100, 300),
                arguments("Gemini thinking only", "gemini",
                        usage("prompt_token_count", 1000, "candidates_token_count", 500, "thoughts_token_count", 500,
                                "total_token_count", 2000),
                        1000, 1000, 2000),
                arguments("Gemini tool use explicitly zero", "gemini",
                        usage("prompt_token_count", 200, "candidates_token_count", 100,
                                "tool_use_prompt_token_count", 0, "total_token_count", 300),
                        200, 100, 300),
                arguments("Mistral", "mistral",
                        usage("prompt_tokens", 22, "completion_tokens", 3, "total_tokens", 25,
                                "prompt_tokens_details.cached_tokens", 0),
                        22, 3, 25),
                arguments("TypeSafe", "typesafe", usage("input_tokens", 120, "output_tokens", 12), 120, 12, 132),
                arguments("Usage of an unknown provider", null,
                        usage("prompt_tokens", 20, "completion_tokens", 10, "some_provider_field", 5), 20, 10, 30));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void toUsageMapKeepsWhatTheSdkSends(String description, String provider, Map<String, Integer> sdkUsage) {
        var spanUsage = SpanUsageFactory.create(provider, sdkUsage, UsageContext.NONE);

        assertThat(spanUsage.toUsageMap()).isEqualTo(sdkUsage);
    }

    static Stream<Arguments> toUsageMapKeepsWhatTheSdkSends() {
        return Stream.of(
                arguments("OpenAI Chat Completions", "openai",
                        usage("completion_tokens", 100, "prompt_tokens", 200, "total_tokens", 300,
                                "original_usage.completion_tokens", 100, "original_usage.prompt_tokens", 200,
                                "original_usage.total_tokens", 300,
                                "original_usage.completion_tokens_details.accepted_prediction_tokens", 50,
                                "original_usage.completion_tokens_details.audio_tokens", 20,
                                "original_usage.prompt_tokens_details.audio_tokens", 10,
                                "original_usage.prompt_tokens_details.cached_tokens", 30)),
                arguments("Gemini", "gemini",
                        usage("completion_tokens", 100, "prompt_tokens", 200, "total_tokens", 300,
                                "original_usage.candidates_token_count", 100,
                                "original_usage.prompt_token_count", 200, "original_usage.total_token_count", 300,
                                "original_usage.cached_content_token_count", 50)),
                arguments("Gemini tool use with thoughts", "gemini",
                        usage("prompt_tokens", 10336, "completion_tokens", 76, "total_tokens", 10412,
                                "original_usage.prompt_token_count", 27, "original_usage.candidates_token_count", 45,
                                "original_usage.thoughts_token_count", 31,
                                "original_usage.tool_use_prompt_token_count", 10309,
                                "original_usage.total_token_count", 10412)),
                arguments("Anthropic", "anthropic",
                        usage("completion_tokens", 100, "prompt_tokens", 280, "total_tokens", 380,
                                "original_usage.input_tokens", 200, "original_usage.output_tokens", 100,
                                "original_usage.cache_creation_input_tokens", 50,
                                "original_usage.cache_read_input_tokens", 30)),
                arguments("Bedrock Converse", "bedrock",
                        usage("completion_tokens", 100, "prompt_tokens", 280, "total_tokens", 380,
                                "original_usage.inputTokens", 200, "original_usage.outputTokens", 100,
                                "original_usage.cacheWriteInputTokens", 50, "original_usage.cacheReadInputTokens", 30)),
                arguments("Mistral", "mistral",
                        usage("prompt_tokens", 22, "completion_tokens", 3, "total_tokens", 25,
                                "original_usage.prompt_tokens", 22, "original_usage.completion_tokens", 3,
                                "original_usage.total_tokens", 25,
                                "original_usage.prompt_tokens_details.cached_tokens", 0)),
                arguments("TypeSafe", "typesafe",
                        usage("prompt_tokens", 120, "completion_tokens", 12, "total_tokens", 132,
                                "original_usage.input_tokens", 120, "original_usage.output_tokens", 12)),
                arguments("Unknown provider, no provider sent", null,
                        usage("completion_tokens", 100, "prompt_tokens", 200, "total_tokens", 300,
                                "original_usage.prompt_tokens", 200, "original_usage.completion_tokens", 100)),
                arguments("Unknown provider, provider sent", "groq",
                        usage("completion_tokens", 100, "prompt_tokens", 200, "total_tokens", 300,
                                "original_usage.prompt_tokens", 200, "original_usage.completion_tokens", 100)));
    }
}
