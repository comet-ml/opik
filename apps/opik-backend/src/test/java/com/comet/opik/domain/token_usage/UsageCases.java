package com.comet.opik.domain.token_usage;

import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.BedrockConverseNativeUsage;
import com.comet.opik.domain.token_usage.model.GeminiNativeUsage;
import com.comet.opik.domain.token_usage.model.NativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every known input variant of every official format, each with the native record it must produce. Shared by the
 * normalization tests, the invariants, the round trip and the check that no variant is left without a case. Numbers
 * come from the SDK's {@code llm_usage} tests and from production samples where noted. Every case is consistent usage:
 * contradictions such as an input below its own cache are tested where the warning is.
 */
public final class UsageCases {

    public record UsageCase(String description, String provider, Map<String, Integer> usage, NativeUsage expected) {

        @Override
        public String toString() {
            return description;
        }
    }

    private UsageCases() {
    }

    public static Stream<UsageCase> all() {
        return Stream.of(anthropic(), openAi(), gemini(), bedrockConverse()).flatMap(cases -> cases);
    }

    public static Stream<UsageCase> anthropic() {
        return Stream.of(
                new UsageCase("Anthropic native without cache", "anthropic",
                        usage("input_tokens", 200, "output_tokens", 100),
                        anthropic("anthropic_native").inputTokens(200).outputTokens(100).build()),
                new UsageCase("Anthropic native with cache read and write (SDK fixture)", "anthropic",
                        usage("input_tokens", 200, "output_tokens", 100, "cache_creation_input_tokens", 50,
                                "cache_read_input_tokens", 30),
                        anthropic("anthropic_native").inputTokens(200).cacheReadInputTokens(30)
                                .cacheCreation5mInputTokens(50).outputTokens(100).build()),
                new UsageCase("Anthropic native with cache writes split by TTL", "anthropic",
                        usage("input_tokens", 100, "output_tokens", 10, "cache_read_input_tokens", 20,
                                "cache_creation_input_tokens", 300, "cache_creation.ephemeral_5m_input_tokens", 100,
                                "cache_creation.ephemeral_1h_input_tokens", 200),
                        anthropic("anthropic_native").inputTokens(100).cacheReadInputTokens(20)
                                .cacheCreation5mInputTokens(100).cacheCreation1hInputTokens(200).outputTokens(10)
                                .build()),
                new UsageCase("Anthropic native in camelCase", "anthropic",
                        usage("inputTokens", 200, "outputTokens", 100, "cacheReadInputTokens", 30),
                        anthropic("anthropic_native").inputTokens(200).cacheReadInputTokens(30).outputTokens(100)
                                .build()),
                new UsageCase("Anthropic native logged by the SDK", "anthropic",
                        usage("prompt_tokens", 280, "completion_tokens", 100, "total_tokens", 380,
                                "original_usage.input_tokens", 200, "original_usage.output_tokens", 100,
                                "original_usage.cache_creation_input_tokens", 50,
                                "original_usage.cache_read_input_tokens", 30),
                        anthropic("anthropic_native").inputTokens(200).cacheReadInputTokens(30)
                                .cacheCreation5mInputTokens(50).outputTokens(100).build()),
                new UsageCase("Anthropic compaction iterations (SDK fixture)", "anthropic",
                        usage("input_tokens", 23000, "output_tokens", 1000, "cache_creation_input_tokens", 0,
                                "cache_read_input_tokens", 0,
                                "iterations.0.input_tokens", 180000, "iterations.0.output_tokens", 3500,
                                "iterations.0.cache_creation_input_tokens", 0, "iterations.0.cache_read_input_tokens",
                                0,
                                "iterations.1.input_tokens", 23000, "iterations.1.output_tokens", 1000,
                                "iterations.1.cache_creation_input_tokens", 0, "iterations.1.cache_read_input_tokens",
                                0),
                        anthropic("anthropic_native").inputTokens(203000).outputTokens(4500).build()),
                new UsageCase("Anthropic compaction iterations with caching (SDK fixture)", "anthropic",
                        usage("input_tokens", 23000, "output_tokens", 1000, "cache_creation_input_tokens", 500,
                                "cache_read_input_tokens", 5000,
                                "iterations.0.input_tokens", 180000, "iterations.0.output_tokens", 3500,
                                "iterations.0.cache_read_input_tokens", 10000,
                                "iterations.0.cache_creation_input_tokens", 2000,
                                "iterations.1.input_tokens", 23000, "iterations.1.output_tokens", 1000,
                                "iterations.1.cache_read_input_tokens", 5000,
                                "iterations.1.cache_creation_input_tokens", 500),
                        anthropic("anthropic_native").inputTokens(203000).cacheReadInputTokens(15000)
                                .cacheCreation5mInputTokens(2500).outputTokens(4500).build()),
                new UsageCase("Anthropic through the OTel GenAI standard", "anthropic",
                        usage("gen_ai.usage.input_tokens", 12000, "gen_ai.usage.output_tokens", 500,
                                "gen_ai.usage.cache_read.input_tokens", 10000),
                        anthropic("otel_genai").inputTokens(2000).cacheReadInputTokens(10000).outputTokens(500)
                                .build()),
                new UsageCase("Anthropic through the OTel GenAI standard with cache writes", "anthropic",
                        usage("input_tokens", 12000, "output_tokens", 500, "cache_read.input_tokens", 6000,
                                "cache_creation.input_tokens", 4000),
                        anthropic("otel_genai").inputTokens(2000).cacheReadInputTokens(6000)
                                .cacheCreation5mInputTokens(4000).outputTokens(500).build()),
                new UsageCase("Anthropic through flat OTel with Anthropic's cache names", "anthropic",
                        usage("gen_ai.usage.input_tokens", 2000, "gen_ai.usage.output_tokens", 500,
                                "gen_ai.usage.cache_read_input_tokens", 10000),
                        anthropic("anthropic_native").inputTokens(2000).cacheReadInputTokens(10000).outputTokens(500)
                                .build()),
                new UsageCase("Anthropic logged through LiteLLM by the SDK (production sample)", "anthropic",
                        usage("prompt_tokens", 12000, "completion_tokens", 500, "total_tokens", 12500,
                                "original_usage.prompt_tokens", 12000, "original_usage.completion_tokens", 500,
                                "original_usage.total_tokens", 12500, "original_usage.cache_read_input_tokens", 10000,
                                "original_usage.prompt_tokens_details.cached_tokens", 10000),
                        anthropic("litellm").inputTokens(2000).cacheReadInputTokens(10000).outputTokens(500).build()),
                new UsageCase("Anthropic through LiteLLM with only prompt_tokens_details", "anthropic",
                        usage("prompt_tokens", 1500, "completion_tokens", 50, "prompt_tokens_details.cached_tokens",
                                1000),
                        anthropic("litellm").inputTokens(500).cacheReadInputTokens(1000).outputTokens(50).build()),
                new UsageCase("Anthropic through LiteLLM with cache writes split by TTL", "anthropic",
                        usage("prompt_tokens", 5000, "completion_tokens", 100, "cache_read_input_tokens", 1000,
                                "cache_creation_input_tokens", 3000, "prompt_tokens_details.cached_tokens", 1000,
                                "prompt_tokens_details.cache_creation_tokens", 3000,
                                "cache_creation_token_details.ephemeral_5m_input_tokens", 1000,
                                "cache_creation_token_details.ephemeral_1h_input_tokens", 2000),
                        anthropic("litellm").inputTokens(1000).cacheReadInputTokens(1000)
                                .cacheCreation5mInputTokens(1000)
                                .cacheCreation1hInputTokens(2000).outputTokens(100).build()),
                new UsageCase("Anthropic through OpenInference", "anthropic",
                        usage("llm.token_count.prompt", 12000, "llm.token_count.completion", 500,
                                "llm.token_count.total", 12500, "llm.token_count.prompt_details.cache_read", 10000),
                        anthropic("openinference").inputTokens(2000).cacheReadInputTokens(10000).outputTokens(500)
                                .build()),
                new UsageCase("Anthropic through OpenInference with cache writes", "anthropic",
                        usage("llm.token_count.prompt", 12000, "llm.token_count.completion", 500,
                                "llm.token_count.prompt_details.cache_read", 6000,
                                "llm.token_count.prompt_details.cache_write", 4000),
                        anthropic("openinference").inputTokens(2000).cacheReadInputTokens(6000)
                                .cacheCreation5mInputTokens(4000).outputTokens(500).build()),
                new UsageCase("Anthropic with only the three totals", "anthropic",
                        usage("prompt_tokens", 300, "completion_tokens", 40, "total_tokens", 340),
                        anthropic("totals_only").inputTokens(300).outputTokens(40).build()),
                new UsageCase("Claude through the OpenAI Agents SDK", "anthropic",
                        usage("input_tokens", 1000, "output_tokens", 100, "input_tokens_details.cached_tokens", 200),
                        anthropic("openai_responses").inputTokens(800).cacheReadInputTokens(200).outputTokens(100)
                                .build()),
                new UsageCase("Claude on Vertex", "vertex_ai-anthropic_models",
                        usage("input_tokens", 50, "output_tokens", 5),
                        AnthropicNativeUsage.builder().provider("vertex_ai-anthropic_models")
                                .variant("anthropic_native").inputTokens(50).outputTokens(5).build()));
    }

    public static Stream<UsageCase> openAi() {
        return Stream.of(
                new UsageCase("Chat Completions with cache and audio (SDK fixture)", "openai",
                        usage("completion_tokens", 100, "prompt_tokens", 200, "total_tokens", 300,
                                "completion_tokens_details.accepted_prediction_tokens", 50,
                                "completion_tokens_details.audio_tokens", 20, "prompt_tokens_details.audio_tokens", 10,
                                "prompt_tokens_details.cached_tokens", 30),
                        openAi("chat_completions").promptTokens(200).cachedTokens(30).audioPromptTokens(10)
                                .completionTokens(100).audioCompletionTokens(20).totalTokens(300).build()),
                new UsageCase("Chat Completions with reasoning", "openai",
                        usage("prompt_tokens", 1000, "completion_tokens", 600, "total_tokens", 1600,
                                "completion_tokens_details.reasoning_tokens", 400),
                        openAi("chat_completions").promptTokens(1000).completionTokens(600).reasoningTokens(400)
                                .totalTokens(1600).build()),
                new UsageCase("Chat Completions logged by the SDK", "openai",
                        usage("prompt_tokens", 200, "completion_tokens", 100, "total_tokens", 300,
                                "original_usage.prompt_tokens", 200, "original_usage.completion_tokens", 100,
                                "original_usage.total_tokens", 300,
                                "original_usage.prompt_tokens_details.cached_tokens",
                                30),
                        openAi("chat_completions").promptTokens(200).cachedTokens(30).completionTokens(100)
                                .totalTokens(300).build()),
                new UsageCase("Responses API", "openai",
                        usage("input_tokens", 1500, "output_tokens", 300, "total_tokens", 1800,
                                "input_tokens_details.cached_tokens", 1000, "output_tokens_details.reasoning_tokens",
                                200),
                        openAi("responses").promptTokens(1500).cachedTokens(1000).completionTokens(300)
                                .reasoningTokens(200).totalTokens(1800).build()),
                new UsageCase("OpenAI Agents SDK, request count ignored", "openai",
                        usage("requests", 2, "input_tokens", 1500, "output_tokens", 300, "total_tokens", 1800,
                                "input_tokens_details.cached_tokens", 500, "output_tokens_details.reasoning_tokens", 0),
                        openAi("responses").promptTokens(1500).cachedTokens(500).completionTokens(300)
                                .totalTokens(1800).build()),
                new UsageCase("OpenAI through flat OTel", "openai",
                        usage("gen_ai.usage.input_tokens", 12000, "gen_ai.usage.output_tokens", 500,
                                "gen_ai.usage.cache_read_input_tokens", 10000),
                        openAi("responses").promptTokens(12000).cachedTokens(10000).completionTokens(500).build()),
                new UsageCase("OpenAI through the OTel GenAI standard", "openai",
                        usage("gen_ai.usage.input_tokens", 12000, "gen_ai.usage.output_tokens", 500,
                                "gen_ai.usage.cache_read.input_tokens", 10000,
                                "gen_ai.usage.reasoning.output_tokens", 100),
                        openAi("responses").promptTokens(12000).cachedTokens(10000).completionTokens(500)
                                .reasoningTokens(100).build()),
                new UsageCase("DeepSeek cache hits", "deepseek",
                        usage("prompt_tokens", 12000, "completion_tokens", 500, "total_tokens", 12500,
                                "prompt_cache_hit_tokens", 10000, "prompt_cache_miss_tokens", 2000),
                        OpenAiNativeUsage.builder().provider("deepseek").variant("deepseek").promptTokens(12000)
                                .cachedTokens(10000).completionTokens(500).totalTokens(12500).build()),
                new UsageCase("Flattened detail leaves (OpenRouter Broadcast)", "openrouter",
                        usage("prompt_tokens", 1000, "completion_tokens", 200, "total_tokens", 1200, "cached_tokens",
                                300, "reasoning_tokens", 50),
                        OpenAiNativeUsage.builder().provider("openrouter").variant("chat_completions")
                                .promptTokens(1000).cachedTokens(300).completionTokens(200).reasoningTokens(50)
                                .totalTokens(1200).build()),
                new UsageCase("OpenAI through OpenInference", "openai",
                        usage("llm.token_count.prompt", 800, "llm.token_count.completion", 200,
                                "llm.token_count.total", 1000, "llm.token_count.prompt_details.cache_read", 300,
                                "llm.token_count.completion_details.reasoning", 50),
                        openAi("openinference").promptTokens(800).cachedTokens(300).completionTokens(200)
                                .reasoningTokens(50).totalTokens(1000).build()),
                new UsageCase("Mistral on the OpenAI default", "mistral",
                        usage("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30),
                        OpenAiNativeUsage.builder().provider("mistral").variant("chat_completions").promptTokens(10)
                                .completionTokens(20).totalTokens(30).build()),
                new UsageCase("Input and output only (TypeSafe)", "typesafe",
                        usage("input_tokens", 7, "output_tokens", 3),
                        OpenAiNativeUsage.builder().provider("typesafe").variant("responses").promptTokens(7)
                                .completionTokens(3).build()),
                new UsageCase("Azure OpenAI", "azure",
                        usage("prompt_tokens", 40, "completion_tokens", 2, "total_tokens", 42),
                        OpenAiNativeUsage.builder().provider("azure").variant("chat_completions").promptTokens(40)
                                .completionTokens(2).totalTokens(42).build()));
    }

    public static Stream<UsageCase> gemini() {
        return Stream.of(
                new UsageCase("Gemini native (SDK fixture)", "gemini",
                        usage("candidates_token_count", 100, "prompt_token_count", 200, "total_token_count", 300,
                                "cached_content_token_count", 50),
                        gemini("gemini_native").promptTokenCount(200).cachedContentTokenCount(50)
                                .candidatesTokenCount(100).totalTokenCount(300).build()),
                new UsageCase("Gemini native with thinking (SDK fixture)", "gemini",
                        usage("prompt_token_count", 1000, "candidates_token_count", 500, "thoughts_token_count", 500,
                                "total_token_count", 2000),
                        gemini("gemini_native").promptTokenCount(1000).candidatesTokenCount(500)
                                .thoughtsTokenCount(500).totalTokenCount(2000).build()),
                new UsageCase("Gemini native with tool use and thinking (SDK fixture)", "gemini",
                        usage("prompt_token_count", 27, "candidates_token_count", 45, "thoughts_token_count", 31,
                                "tool_use_prompt_token_count", 10309, "total_token_count", 10412),
                        gemini("gemini_native").promptTokenCount(27).toolUsePromptTokenCount(10309)
                                .candidatesTokenCount(45).thoughtsTokenCount(31).totalTokenCount(10412).build()),
                new UsageCase("Gemini native with tool use, no thinking (SDK fixture)", "gemini",
                        usage("prompt_token_count", 1041, "candidates_token_count", 902,
                                "tool_use_prompt_token_count", 377545, "total_token_count", 379488),
                        gemini("gemini_native").promptTokenCount(1041).toolUsePromptTokenCount(377545)
                                .candidatesTokenCount(902).totalTokenCount(379488).build()),
                new UsageCase("Gemini native in camelCase (JS SDK)", "gemini",
                        usage("promptTokenCount", 200, "candidatesTokenCount", 100, "totalTokenCount", 300,
                                "thoughtsTokenCount", 0),
                        gemini("gemini_native").promptTokenCount(200).candidatesTokenCount(100).totalTokenCount(300)
                                .build()),
                new UsageCase("Gemini on Vertex without candidates (SDK fixture)", "vertex_ai-language-models",
                        usage("prompt_token_count", 7859, "total_token_count", 7859),
                        GeminiNativeUsage.builder().provider("vertex_ai-language-models").variant("gemini_native")
                                .promptTokenCount(7859).totalTokenCount(7859).build()),
                new UsageCase("Gemini logged by the SDK through Google ADK (production sample)", "gemini",
                        usage("prompt_tokens", 5266, "completion_tokens", 1415, "total_tokens", 6681,
                                "original_usage.prompt_token_count", 5266, "original_usage.candidates_token_count", 425,
                                "original_usage.thoughts_token_count", 990, "original_usage.total_token_count", 6681),
                        gemini("gemini_native").promptTokenCount(5266).candidatesTokenCount(425)
                                .thoughtsTokenCount(990).totalTokenCount(6681).build()),
                new UsageCase("Gemini through LiteLLM", "gemini",
                        usage("prompt_tokens", 4000, "completion_tokens", 1500, "total_tokens", 5500,
                                "cache_read_input_tokens", 3000, "completion_tokens_details.reasoning_tokens", 900),
                        gemini("openai_style").promptTokenCount(4000).cachedContentTokenCount(3000)
                                .candidatesTokenCount(600).thoughtsTokenCount(900).totalTokenCount(5500).build()),
                new UsageCase("Gemini through LiteLLM with prompt_tokens_details", "gemini",
                        usage("prompt_tokens", 4000, "completion_tokens", 600, "prompt_tokens_details.cached_tokens",
                                1000),
                        gemini("openai_style").promptTokenCount(4000).cachedContentTokenCount(1000)
                                .candidatesTokenCount(600).build()),
                new UsageCase("Gemini through the OpenAI Agents SDK", "gemini",
                        usage("input_tokens", 4000, "output_tokens", 1500, "total_tokens", 5500,
                                "input_tokens_details.cached_tokens", 3000, "output_tokens_details.reasoning_tokens",
                                900),
                        gemini("responses_style").promptTokenCount(4000).cachedContentTokenCount(3000)
                                .candidatesTokenCount(600).thoughtsTokenCount(900).totalTokenCount(5500).build()),
                new UsageCase("Gemini through the OTel GenAI standard", "gemini",
                        usage("gen_ai.usage.input_tokens", 4000, "gen_ai.usage.output_tokens", 1500,
                                "gen_ai.usage.cache_read.input_tokens", 3000,
                                "gen_ai.usage.reasoning.output_tokens", 900),
                        gemini("responses_style").promptTokenCount(4000).cachedContentTokenCount(3000)
                                .candidatesTokenCount(600).thoughtsTokenCount(900).build()),
                new UsageCase("Gemini under LiteLLM's Vertex provider", "vertex_ai",
                        usage("prompt_token_count", 20, "candidates_token_count", 10, "total_token_count", 30),
                        GeminiNativeUsage.builder().provider("vertex_ai").variant("gemini_native").promptTokenCount(20)
                                .candidatesTokenCount(10).totalTokenCount(30).build()));
    }

    public static Stream<UsageCase> bedrockConverse() {
        return Stream.of(
                new UsageCase("Bedrock Converse with cache (SDK fixture)", "bedrock",
                        usage("inputTokens", 200, "outputTokens", 100, "cacheWriteInputTokens", 50,
                                "cacheReadInputTokens", 30),
                        bedrock("converse").inputTokens(200).cacheReadInputTokens(30).cacheWriteInputTokens(50)
                                .outputTokens(100).build()),
                new UsageCase("Bedrock Converse without cache (SDK fixture)", "bedrock",
                        usage("inputTokens", 200, "outputTokens", 100, "totalTokens", 300),
                        bedrock("converse").inputTokens(200).outputTokens(100).build()),
                new UsageCase("Bedrock Converse with cache counters at zero (SDK fixture)", "bedrock",
                        usage("inputTokens", 200, "outputTokens", 100, "totalTokens", 300, "cacheReadInputTokens", 0,
                                "cacheWriteInputTokens", 0),
                        bedrock("converse").inputTokens(200).outputTokens(100).build()),
                new UsageCase("Bedrock InvokeModel with a Claude body", "bedrock",
                        usage("input_tokens", 12, "output_tokens", 7, "cache_creation_input_tokens", 1024,
                                "cache_read_input_tokens", 4096),
                        bedrock("converse").inputTokens(12).cacheReadInputTokens(4096).cacheWriteInputTokens(1024)
                                .outputTokens(7).build()),
                new UsageCase("Bedrock InvokeModel with a Llama body", "bedrock",
                        usage("prompt_token_count", 120, "generation_token_count", 45),
                        bedrock("llama").inputTokens(120).outputTokens(45).build()),
                new UsageCase("Bedrock through LiteLLM (cache excluded, as measured)", "bedrock",
                        usage("prompt_tokens", 200, "completion_tokens", 100, "cache_read_input_tokens", 4000,
                                "cache_creation_input_tokens", 1000),
                        bedrock("litellm").inputTokens(200).cacheReadInputTokens(4000).cacheWriteInputTokens(1000)
                                .outputTokens(100).build()),
                new UsageCase("Bedrock InvokeModel with a Mistral body", "bedrock",
                        usage("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30),
                        bedrock("totals_only").inputTokens(10).outputTokens(20).build()),
                new UsageCase("Bedrock Converse logged by the SDK", "bedrock",
                        usage("prompt_tokens", 280, "completion_tokens", 100, "total_tokens", 380,
                                "original_usage.inputTokens", 200, "original_usage.outputTokens", 100,
                                "original_usage.cacheWriteInputTokens", 50, "original_usage.cacheReadInputTokens", 30),
                        bedrock("converse").inputTokens(200).cacheReadInputTokens(30).cacheWriteInputTokens(50)
                                .outputTokens(100).build()),
                new UsageCase("Bedrock under LiteLLM's Converse provider", "bedrock_converse",
                        usage("inputTokens", 9, "outputTokens", 1),
                        BedrockConverseNativeUsage.builder().provider("bedrock_converse").variant("converse")
                                .inputTokens(9).outputTokens(1).build()));
    }

    public static Map<String, Integer> usage(Object... keysAndValues) {
        var usage = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            usage.put((String) keysAndValues[i], (Integer) keysAndValues[i + 1]);
        }
        return usage;
    }

    private static AnthropicNativeUsage.AnthropicNativeUsageBuilder anthropic(String variant) {
        return AnthropicNativeUsage.builder().provider("anthropic").variant(variant);
    }

    private static OpenAiNativeUsage.OpenAiNativeUsageBuilder openAi(String variant) {
        return OpenAiNativeUsage.builder().provider("openai").variant(variant);
    }

    private static GeminiNativeUsage.GeminiNativeUsageBuilder gemini(String variant) {
        return GeminiNativeUsage.builder().provider("gemini").variant(variant);
    }

    private static BedrockConverseNativeUsage.BedrockConverseNativeUsageBuilder bedrock(String variant) {
        return BedrockConverseNativeUsage.builder().provider("bedrock").variant(variant);
    }
}
