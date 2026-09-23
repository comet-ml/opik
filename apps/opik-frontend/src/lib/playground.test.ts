import { describe, expect, it } from "vitest";
import {
  getDefaultConfigByProvider,
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
