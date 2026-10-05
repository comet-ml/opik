import { describe, expect, it, vi } from "vitest";
import {
  createCompletionAnnouncer,
  describeRunFailure,
  getDefaultConfigByProvider,
  hasUnsupportedMedia,
  restoreMissingConfigKeys,
} from "@/lib/playground";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, LLMMessage, MessageContent } from "@/types/llm";
import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";

const userMessage = (content: MessageContent): LLMMessage => ({
  id: "message",
  role: LLM_MESSAGE_ROLE.user,
  content,
});

const imageMessage = userMessage([
  { type: "text", text: "Describe this" },
  { type: "image_url", image_url: { url: "https://example.com/cat.png" } },
]);

const videoMessage = userMessage([
  { type: "video_url", video_url: { url: "https://example.com/cat.mp4" } },
]);

describe("hasUnsupportedMedia", () => {
  it("returns false when no model is selected", () => {
    expect(hasUnsupportedMedia({ model: "", messages: [imageMessage] })).toBe(
      false,
    );
  });

  it("returns false for text-only prompts on a non-vision model", () => {
    expect(
      hasUnsupportedMedia({
        model: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO,
        messages: [userMessage("Hello")],
      }),
    ).toBe(false);
  });

  it("returns false for images on a vision model", () => {
    expect(
      hasUnsupportedMedia({
        model: PROVIDER_MODEL_TYPE.GPT_4O,
        messages: [imageMessage],
      }),
    ).toBe(false);
  });

  it("returns true for images on a non-vision model", () => {
    expect(
      hasUnsupportedMedia({
        model: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO,
        messages: [userMessage("Hello"), imageMessage],
      }),
    ).toBe(true);
  });

  it("returns true for videos on a non-vision model", () => {
    expect(
      hasUnsupportedMedia({
        model: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO,
        messages: [videoMessage],
      }),
    ).toBe(true);
  });
});

describe("getDefaultConfigByProvider — Anthropic", () => {
  it("seeds temperature default for models that accept sampling params", () => {
    const config = getDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
    ) as LLMAnthropicConfigsType;

    expect(config.temperature).toBe(0);
  });

  it("omits temperature and topP for Claude Opus 4.7", () => {
    const config = getDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
    ) as LLMAnthropicConfigsType;

    expect(config.temperature).toBeUndefined();
    expect(config.topP).toBeUndefined();
    expect(config.maxCompletionTokens).toBe(4000);
  });
});

describe("getDefaultConfigByProvider — OpenAI", () => {
  const defaults = (model: PROVIDER_MODEL_TYPE) =>
    getDefaultConfigByProvider(
      PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE,
      model,
    ) as LLMOpenAIConfigsType;

  it.each([
    PROVIDER_MODEL_TYPE.GPT_6_ASTRA,
    PROVIDER_MODEL_TYPE.GPT_6_1_SOL,
    PROVIDER_MODEL_TYPE.GPT_6_SOL,
    PROVIDER_MODEL_TYPE.GPT_6_LUNA,
    PROVIDER_MODEL_TYPE.GPT_5_6_LUNA,
    PROVIDER_MODEL_TYPE.GPT_5_4,
    PROVIDER_MODEL_TYPE.GPT_5,
    PROVIDER_MODEL_TYPE.GPT_O1,
  ])("seeds %s as a reasoning model with high effort", (model) => {
    // Not 1: the request omits temperature for reasoning models anyway, and a seeded 1 would carry
    // over to the next chat model the user picks.
    expect(defaults(model)).toMatchObject({
      temperature: 0,
      reasoningEffort: "high",
    });
  });

  it("seeds o1-mini as a reasoning model without an effort", () => {
    const config = defaults(PROVIDER_MODEL_TYPE.GPT_O1_MINI);

    expect(config.temperature).toBe(0);
    expect(config).not.toHaveProperty("reasoningEffort");
  });

  it.each([
    PROVIDER_MODEL_TYPE.GPT_5_CHAT_LATEST,
    PROVIDER_MODEL_TYPE.GPT_5_1_CHAT_LATEST,
    PROVIDER_MODEL_TYPE.GPT_5_2_CHAT_LATEST,
    PROVIDER_MODEL_TYPE.GPT_5_3_CHAT_LATEST,
    PROVIDER_MODEL_TYPE.GPT_4O,
  ])("seeds %s as a chat model without an effort", (model) => {
    const config = defaults(model);

    expect(config.temperature).toBe(0);
    expect(config).not.toHaveProperty("reasoningEffort");
  });
});

describe("getDefaultConfigByProvider — Anthropic thinking effort", () => {
  it.each([
    PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
  ])("seeds no thinkingEffort for %s while the backend drops it", (model) => {
    const config = getDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      model,
    );

    expect(config).not.toHaveProperty("thinkingEffort");
  });
});

describe("restoreMissingConfigKeys", () => {
  const prompt = (
    provider: PROVIDER_TYPE,
    model: PROVIDER_MODEL_TYPE,
    configs: Record<string, unknown>,
  ) =>
    ({
      name: "p",
      id: "p1",
      messages: [],
      model,
      provider: provider as COMPOSED_PROVIDER_TYPE,
      configs,
    }) as unknown as PlaygroundPromptType;

  it("puts back a topP the old model-change reconciler dropped", () => {
    const restored = restoreMissingConfigKeys(
      prompt(PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI, {
        temperature: 0.4,
        maxCompletionTokens: 4000,
        frequencyPenalty: 0,
        presencePenalty: 0,
      }),
    );

    expect((restored.configs as LLMOpenAIConfigsType).topP).toBe(1);
    expect((restored.configs as LLMOpenAIConfigsType).temperature).toBe(0.4);
  });

  it("puts back parameters added after the prompt was persisted", () => {
    const restored = restoreMissingConfigKeys(
      prompt(PROVIDER_TYPE.OPEN_ROUTER, PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, {
        temperature: 1,
        topP: 1,
        maxTokens: 0,
      }),
    );

    expect(restored.configs).toMatchObject({ minP: 0, topA: 0 });
  });

  it("leaves a cleared Anthropic temperature cleared when Top P is the live half", () => {
    // Restoring temperature here would silently override the user's Top P: with both set the
    // request drops Top P. Which half is live stays resolveSamplingParams' call.
    const restored = restoreMissingConfigKeys(
      prompt(PROVIDER_TYPE.ANTHROPIC, PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        topP: 0.9,
        maxCompletionTokens: 4000,
      }),
    );

    expect(
      (restored.configs as LLMAnthropicConfigsType).temperature,
    ).toBeUndefined();
    expect((restored.configs as LLMAnthropicConfigsType).topP).toBe(0.9);
  });

  it("leaves a Claude on another provider unselected rather than picking temperature", () => {
    // The exclusive rule follows the model, not the route: filling in OpenRouter's own temperature
    // and topP defaults would turn a deliberate "send neither" back into temperature-at-default.
    const restored = restoreMissingConfigKeys(
      prompt(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_6,
        { maxTokens: 0 },
      ),
    );

    expect(restored.configs).toMatchObject({ minP: 0, topA: 0 });
    expect(restored.configs).not.toHaveProperty("temperature");
    expect(restored.configs).not.toHaveProperty("topP");
  });

  it("returns the same prompt when nothing is missing", () => {
    const complete = prompt(
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      getDefaultConfigByProvider(
        PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE,
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      ) as unknown as Record<string, unknown>,
    );

    expect(restoreMissingConfigKeys(complete)).toBe(complete);
  });

  it("does not throw on a prompt persisted without a config", () => {
    // It runs over every persisted prompt during store hydration, so a throw here costs the whole
    // playground state, not one prompt.
    const restored = restoreMissingConfigKeys({
      name: "p",
      id: "p1",
      messages: [],
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      provider: PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE,
    } as unknown as PlaygroundPromptType);

    expect((restored.configs as LLMOpenAIConfigsType).topP).toBe(1);
  });

  it("treats a null value as missing", () => {
    const restored = restoreMissingConfigKeys(
      prompt(PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI, {
        temperature: 0.4,
        topP: null,
      }),
    );

    expect((restored.configs as LLMOpenAIConfigsType).topP).toBe(1);
  });

  it("does not throw on a malformed prompt entry", () => {
    expect(
      restoreMissingConfigKeys(null as unknown as PlaygroundPromptType),
    ).toBeNull();
    expect(
      restoreMissingConfigKeys("nonsense" as unknown as PlaygroundPromptType),
    ).toBe("nonsense");
  });

  it("does not throw on a prompt whose stored provider is not a string", () => {
    // parseComposedProviderType calls provider.startsWith, so a corrupted entry would throw inside
    // the hydration map and take every sibling prompt's state with it.
    const malformed = {
      name: "p",
      id: "p1",
      messages: [],
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      provider: { openai: true },
      configs: {},
    } as unknown as PlaygroundPromptType;

    expect(restoreMissingConfigKeys(malformed)).toBe(malformed);
  });

  it("leaves a prompt with no provider alone", () => {
    const noProvider = {
      name: "p",
      id: "p1",
      messages: [],
      model: "",
      provider: "",
      configs: {},
    } as unknown as PlaygroundPromptType;

    expect(restoreMissingConfigKeys(noProvider)).toBe(noProvider);
  });
});

describe("createCompletionAnnouncer", () => {
  it("waits for logging to finish when the registry lands first", () => {
    const announce = vi.fn();
    const announcer = createCompletionAnnouncer(2, announce);

    announcer.experimentsRegistered(2);
    expect(announce).not.toHaveBeenCalled();

    announcer.loggingFinished();
    expect(announce).toHaveBeenCalledTimes(1);
  });

  it("waits for the registry when logging finishes first", () => {
    const announce = vi.fn();
    const announcer = createCompletionAnnouncer(2, announce);

    announcer.loggingFinished();
    expect(announce).not.toHaveBeenCalled();

    announcer.experimentsRegistered(2);
    expect(announce).toHaveBeenCalledTimes(1);
  });

  it("holds until every expected experiment is registered", () => {
    const announce = vi.fn();
    const announcer = createCompletionAnnouncer(2, announce);

    announcer.experimentsRegistered(1);
    announcer.loggingFinished();
    expect(announce).not.toHaveBeenCalled();

    announcer.experimentsRegistered(2);
    expect(announce).toHaveBeenCalledTimes(1);
  });

  it("announces once however many times the signals repeat", () => {
    const announce = vi.fn();
    const announcer = createCompletionAnnouncer(1, announce);

    announcer.experimentsRegistered(1);
    announcer.loggingFinished();
    announcer.experimentsRegistered(1);
    announcer.loggingFinished();

    expect(announce).toHaveBeenCalledTimes(1);
  });

  it("stays silent when a run is interrupted before its experiments exist", () => {
    const announce = vi.fn();
    const announcer = createCompletionAnnouncer(2, announce);

    announcer.loggingFinished();
    announcer.experimentsRegistered(1);

    expect(announce).not.toHaveBeenCalled();
  });

  it("stays silent for a run stopped before its experiments landed", () => {
    // Mirrors how the single-prompt run gates itself: Stop drops the prompt from
    // the live set, and a drain arriving afterwards must not report success.
    const live = new Set(["prompt-1"]);
    const announce = vi.fn();
    const announcer = createCompletionAnnouncer(1, () => {
      if (!live.delete("prompt-1")) return;
      announce();
    });

    live.delete("prompt-1");
    announcer.experimentsRegistered(1);
    announcer.loggingFinished();

    expect(announce).not.toHaveBeenCalled();
  });
});

describe("describeRunFailure", () => {
  const OPENAI_RATE_LIMIT =
    "Rate limit reached for gpt-4o on requests per min (RPM): Limit 3. Please try again in 20s.";

  const failedRun = (
    overrides: Partial<RunStreamingReturn>,
  ): RunStreamingReturn => ({
    result: "",
    startTime: "",
    endTime: "",
    usage: null,
    choices: null,
    providerError: null,
    opikError: null,
    pythonProxyError: null,
    errorStatus: null,
    actualModel: null,
    actualProvider: null,
    ...overrides,
  });

  it("gives a rate-limited run a next step and keeps the provider's message", () => {
    const run = failedRun({ errorStatus: 429, opikError: OPENAI_RATE_LIMIT });

    expect(describeRunFailure(run, true)).toBe(
      "Rate limit reached. Wait a moment and run again. If it keeps happening, lower Max concurrent requests or raise Throttling in Model parameters. " +
        `Provider message: ${OPENAI_RATE_LIMIT}`,
    );
  });

  it("does not point at settings the model has no panel for", () => {
    const run = failedRun({ errorStatus: 429, opikError: OPENAI_RATE_LIMIT });

    const message = describeRunFailure(run, false);

    expect(message).toBe(
      `Rate limit reached. Wait a moment and run again. Provider message: ${OPENAI_RATE_LIMIT}`,
    );
    expect(message).not.toContain("Model parameters");
  });

  it("tells an out-of-credits run to add credits, not to slow down", () => {
    const run = failedRun({
      errorStatus: 402,
      opikError: "You exceeded your current quota.",
    });

    expect(describeRunFailure(run, true)).toBe(
      "Out of credits. Add credits or raise your quota with the provider, then run again. " +
        "Provider message: You exceeded your current quota.",
    );
  });

  it("explains a 429 that came with no readable message", () => {
    const run = failedRun({ errorStatus: 429 });

    expect(describeRunFailure(run, false)).toBe(
      "Rate limit reached. Wait a moment and run again.",
    );
  });

  it.each<[string, Partial<RunStreamingReturn>, string]>([
    [
      "a provider error",
      { errorStatus: 401, providerError: "Invalid key" },
      "Invalid key",
    ],
    [
      "an error with no status",
      { opikError: "Unexpected error" },
      "Unexpected error",
    ],
  ])("leaves %s as the provider wrote it", (_label, overrides, expected) => {
    expect(describeRunFailure(failedRun(overrides), true)).toBe(expected);
  });
});
