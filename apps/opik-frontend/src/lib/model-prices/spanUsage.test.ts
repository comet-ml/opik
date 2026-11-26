import {
  normalizeUsageKey,
  readSpanUsage,
  resolveUsageProvider,
} from "./spanUsage";
import { priceSpan } from "./costBreakdown";
import type { ModelPriceResolution } from "./types";

const resolutionOf = (rates: Record<string, string>): ModelPriceResolution => ({
  model: "m",
  provider: "p",
  cost: "0",
  cost_source: "built_in",
  list_version: 0,
  lines: Object.entries(rates).map(([name, rate]) => ({
    name,
    rate_per_million: rate,
    source: "built_in",
  })),
});

// SpanCostCalculatorTest's per-token rates, per million tokens.
const CACHE_PRICES = resolutionOf({
  input: "10000",
  output: "20000",
  cache_read: "5000",
  cache_write: "15000",
});
const NO_CACHE_PRICES = resolutionOf({ input: "10000", output: "20000" });
const CACHE_READ = resolutionOf({
  input: "10000",
  output: "20000",
  cache_read: "5000",
});
const OPENROUTER_CLAUDE = resolutionOf({
  input: "2",
  output: "10",
  cache_read: "0.2",
  cache_write: "2.5",
});

const usage = (...keysAndValues: (string | number)[]) => {
  const result: Record<string, number> = {};
  for (let index = 0; index < keysAndValues.length; index += 2) {
    result[keysAndValues[index] as string] = keysAndValues[index + 1] as number;
  }
  return result;
};

const costOf = (
  resolution: ModelPriceResolution,
  provider: string | null,
  spanUsage: Record<string, number>,
) =>
  priceSpan({ model: "m", provider, usage: spanUsage }, resolution).linesTotal;

describe("readSpanUsage mirrors SpanCostCalculatorTest", () => {
  it.each([
    [
      "reasoning without a rate of its own bills as output",
      "openai",
      NO_CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        500,
        "completion_tokens_details.reasoning_tokens",
        200,
      ),
      20,
    ],
    [
      "negative reasoning counts as none",
      "openai",
      NO_CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        500,
        "completion_tokens_details.reasoning_tokens",
        -10,
      ),
      20,
    ],
    [
      "flat OTel cache key",
      "openai",
      CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "cache_read_input_tokens",
        200,
      ),
      11,
    ],
    [
      "Chat Completions logged by the SDK",
      "openai",
      CACHE_PRICES,
      usage(
        "cache_read_input_tokens",
        200,
        "original_usage.prompt_tokens",
        1000,
        "original_usage.completion_tokens",
        100,
        "original_usage.prompt_tokens_details.cached_tokens",
        300,
      ),
      10.5,
    ],
    [
      "Responses API logged by the SDK",
      "openai",
      CACHE_PRICES,
      usage(
        "original_usage.input_tokens",
        1000,
        "original_usage.output_tokens",
        100,
        "original_usage.input_tokens_details.cached_tokens",
        300,
      ),
      10.5,
    ],
    [
      "provider without a format of its own",
      "groq",
      CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "prompt_tokens_details.cached_tokens",
        200,
      ),
      11,
    ],
    [
      "LiteLLM, whose prompt leaves the writes out",
      "openrouter",
      OPENROUTER_CLAUDE,
      usage(
        "prompt_tokens",
        4,
        "completion_tokens",
        51,
        "total_tokens",
        55,
        "original_usage.prompt_tokens",
        4,
        "original_usage.completion_tokens",
        51,
        "original_usage.total_tokens",
        55,
        "original_usage.cache_creation_input_tokens",
        22574,
      ),
      0.056953,
    ],
    [
      "flat detail leaves, whose prompt includes the reads and the writes",
      "openrouter",
      OPENROUTER_CLAUDE,
      usage(
        "prompt_tokens",
        78505,
        "completion_tokens",
        1540,
        "total_tokens",
        80045,
        "cache_creation_input_tokens",
        66468,
        "cache_read_input_tokens",
        12036,
        "cached_input_tokens",
        12036,
        "reasoning_tokens",
        1152,
        "prompt_tokens_details.audio_tokens",
        0,
        "completion_tokens_details.audio_tokens",
        0,
      ),
      0.1839792,
    ],
    [
      "a negative reported text count bills no text",
      "openai",
      NO_CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "completion_tokens_details.text_tokens",
        -5,
        "completion_tokens_details.reasoning_tokens",
        10,
      ),
      10,
    ],
    [
      "cached tokens bill as input without a cache rate",
      "openai",
      NO_CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "prompt_tokens_details.cached_tokens",
        200,
      ),
      12,
    ],
    [
      "audio without rates of its own bills as text",
      "openai",
      CACHE_PRICES,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "cache_read_input_tokens",
        200,
        "prompt_tokens_details.audio_tokens",
        300,
        "completion_tokens_details.audio_tokens",
        50,
      ),
      11,
    ],
    [
      "Responses API cached key next to the totals",
      "openai",
      CACHE_READ,
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "original_usage.input_tokens_details.cached_tokens",
        300,
      ),
      10.5,
    ],
  ])("OpenAI: %s", (_name, provider, resolution, spanUsage, expected) => {
    expect(costOf(resolution, provider, spanUsage)).toBeCloseTo(expected, 10);
  });

  it.each([
    [
      "Anthropic native",
      "anthropic",
      usage(
        "input_tokens",
        1000,
        "output_tokens",
        100,
        "cache_read_input_tokens",
        200,
        "cache_creation_input_tokens",
        50,
      ),
    ],
    [
      "Anthropic native logged by the SDK",
      "anthropic",
      usage(
        "prompt_tokens",
        1250,
        "completion_tokens",
        100,
        "total_tokens",
        1350,
        "original_usage.input_tokens",
        1000,
        "original_usage.output_tokens",
        100,
        "original_usage.cache_read_input_tokens",
        200,
        "original_usage.cache_creation_input_tokens",
        50,
      ),
    ],
    [
      "LiteLLM, whose prompt includes the cache and which details it",
      "anthropic",
      usage(
        "prompt_tokens",
        1250,
        "completion_tokens",
        100,
        "total_tokens",
        1350,
        "prompt_tokens_details.cached_tokens",
        200,
        "cache_read_input_tokens",
        200,
        "cache_creation_input_tokens",
        50,
      ),
    ],
    [
      "Anthropic's input renamed prompt_tokens",
      "anthropic",
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "total_tokens",
        1100,
        "cache_read_input_tokens",
        200,
        "cache_creation_input_tokens",
        50,
      ),
    ],
    [
      "Claude on Vertex",
      "vertex_ai-anthropic_models",
      usage(
        "input_tokens",
        1000,
        "output_tokens",
        100,
        "cache_read_input_tokens",
        200,
        "cache_creation_input_tokens",
        50,
      ),
    ],
    [
      "Bedrock Converse",
      "bedrock",
      usage(
        "inputTokens",
        1000,
        "outputTokens",
        100,
        "cacheReadInputTokens",
        200,
        "cacheWriteInputTokens",
        50,
      ),
    ],
    [
      "Bedrock LiteLLM, whose prompt excludes the cache",
      "bedrock",
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "cache_read_input_tokens",
        200,
        "cache_creation_input_tokens",
        50,
      ),
    ],
    [
      "Bedrock Converse provider",
      "bedrock_converse",
      usage(
        "inputTokens",
        1000,
        "outputTokens",
        100,
        "cacheReadInputTokens",
        200,
        "cacheWriteInputTokens",
        50,
      ),
    ],
  ])(
    "bills each cache bucket at its rate: %s",
    (_name, provider, spanUsage) => {
      expect(costOf(CACHE_PRICES, provider, spanUsage)).toBeCloseTo(13.75, 10);
    },
  );

  it("bills Anthropic's cache as input without cache rates", () => {
    expect(
      costOf(
        NO_CACHE_PRICES,
        "anthropic",
        usage(
          "input_tokens",
          1000,
          "output_tokens",
          100,
          "cache_read_input_tokens",
          200,
          "cache_creation_input_tokens",
          50,
        ),
      ),
    ).toBeCloseTo(14.5, 10);
  });

  it("bills 1-hour cache writes at the write rate without a 1-hour rate, and says so", () => {
    const spanUsage = usage(
      "input_tokens",
      1000,
      "output_tokens",
      100,
      "cache_creation.ephemeral_5m_input_tokens",
      50,
      "cache_creation.ephemeral_1h_input_tokens",
      100,
    );

    expect(costOf(CACHE_PRICES, "anthropic", spanUsage)).toBeCloseTo(14.25, 10);
    expect(readSpanUsage(spanUsage, "anthropic").hints).toContain(
      "one_hour_cache_write",
    );
  });

  it.each([
    [
      "context cache is part of the prompt",
      usage(
        "original_usage.prompt_token_count",
        1000,
        "original_usage.candidates_token_count",
        100,
        "original_usage.cached_content_token_count",
        300,
      ),
      10.5,
    ],
    [
      "flat OTel cache key",
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "cache_read_input_tokens",
        200,
      ),
      11,
    ],
    [
      "tool-use prompt tokens are input",
      usage(
        "original_usage.prompt_token_count",
        27,
        "original_usage.candidates_token_count",
        45,
        "original_usage.thoughts_token_count",
        31,
        "original_usage.tool_use_prompt_token_count",
        10309,
      ),
      104.88,
    ],
    [
      "tool use with context cache",
      usage(
        "original_usage.prompt_token_count",
        1000,
        "original_usage.candidates_token_count",
        100,
        "original_usage.tool_use_prompt_token_count",
        500,
        "original_usage.cached_content_token_count",
        300,
      ),
      15.5,
    ],
    [
      "thoughts bill as output even when the total leaves them out",
      usage(
        "original_usage.prompt_token_count",
        1000,
        "original_usage.candidates_token_count",
        100,
        "original_usage.thoughts_token_count",
        50,
        "original_usage.total_token_count",
        1100,
      ),
      13,
    ],
    [
      "cached_content_token_count next to the SDK's completion",
      usage(
        "original_usage.prompt_token_count",
        1000,
        "completion_tokens",
        100,
        "original_usage.cached_content_token_count",
        300,
      ),
      10.5,
    ],
    [
      "prompt_tokens already includes tool use",
      usage(
        "prompt_tokens",
        10336,
        "completion_tokens",
        76,
        "original_usage.tool_use_prompt_token_count",
        10309,
      ),
      104.88,
    ],
  ])("Gemini: %s", (_name, spanUsage, expected) => {
    expect(costOf(CACHE_PRICES, "gemini", spanUsage)).toBeCloseTo(expected, 10);
  });

  it("bills the client's totals when there is no provider", () => {
    expect(
      costOf(
        CACHE_PRICES,
        null,
        usage("prompt_tokens", 1000, "completion_tokens", 100),
      ),
    ).toBeCloseTo(12, 10);
  });

  it("prices empty usage at 0", () => {
    expect(costOf(CACHE_PRICES, "openai", {})).toBe(0);
  });
});

describe("readSpanUsage", () => {
  it("names the keys each bucket came from", () => {
    const reading = readSpanUsage(
      usage(
        "prompt_tokens",
        1000,
        "completion_tokens",
        100,
        "prompt_tokens_details.cached_tokens",
        200,
      ),
      "openai",
    );

    expect(reading.buckets).toEqual([
      {
        name: "input",
        tokens: 800,
        source: "prompt_tokens − prompt_tokens_details.cached_tokens",
      },
      {
        name: "cache_read",
        tokens: 200,
        source: "prompt_tokens_details.cached_tokens",
      },
      { name: "cache_write", tokens: 0, source: "" },
      { name: "output", tokens: 100, source: "completion_tokens" },
    ]);
  });

  it("flags tokens that may bill at rates the explanation doesn't return", () => {
    expect(
      readSpanUsage(
        usage(
          "prompt_tokens",
          250000,
          "completion_tokens",
          100,
          "completion_tokens_details.reasoning_tokens",
          10,
          "prompt_tokens_details.audio_tokens",
          5,
        ),
        "openai",
      ).hints,
    ).toEqual(["audio", "reasoning", "long_prompt"]);
  });

  it("ignores values that aren't numbers", () => {
    expect(
      readSpanUsage({ prompt_tokens: "12", completion_tokens: 3 }, "openai")
        .buckets[3].tokens,
    ).toBe(3);
  });
});

describe("normalizeUsageKey", () => {
  it("matches names ignoring case and separators, without namespaces", () => {
    expect(normalizeUsageKey("cacheReadInputTokens")).toBe(
      "cache_read_input_tokens",
    );
    expect(normalizeUsageKey("Input-Tokens")).toBe("input_tokens");
    expect(normalizeUsageKey("gen_ai.usage.input_tokens")).toBe("input_tokens");
    expect(normalizeUsageKey("cacheDetails.5m.inputTokens")).toBe(
      "cache_details.5m.input_tokens",
    );
  });
});

describe("resolveUsageProvider", () => {
  it("maps the names clients send to the backend's provider names", () => {
    expect(resolveUsageProvider("Google_AI", "gemini-2.5")).toBe("gemini");
    expect(resolveUsageProvider("api.anthropic.com:443", "claude")).toBe(
      "anthropic",
    );
    expect(resolveUsageProvider("openai.internal", "gpt-5")).toBe(
      "openai.internal",
    );
  });

  it("takes the provider from the model's prefix only when it has none", () => {
    expect(resolveUsageProvider("", "anthropic/claude-sonnet-4-5")).toBe(
      "anthropic",
    );
    expect(resolveUsageProvider("openai", "anthropic/claude")).toBe("openai");
    expect(resolveUsageProvider(null, "gpt-5")).toBeUndefined();
  });
});
