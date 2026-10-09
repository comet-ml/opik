import { afterEach, describe, expect, it } from "vitest";
import omit from "lodash/omit";
import {
  getAnthropicThinkingEffortOptions,
  getDefaultThinkingEffort,
  getDefaultThinkingLevel,
  getRoutableProviderModelValue,
  getOpenAIReasoningEffortOptions,
  getThinkingLevelOptions,
  isReasoningModel,
  resolveEffort,
  resolveSamplingParams,
  resolveThinkingLevel,
  SamplingParams,
  sanitizeConfigForRequest,
  supportsAnthropicThinkingEffort,
  supportsGeminiSamplingParams,
  supportsGeminiThinkingLevel,
  supportsOpenAIReasoningEffort,
  supportsPenaltyParams,
  supportsSamplingParams,
  supportsVertexAIThinkingLevel,
  updateProviderConfig,
  withoutThinkingAmount,
  withShownThinkingLevel,
} from "@/lib/modelUtils";
import {
  AnthropicThinkingEffort,
  COMPOSED_PROVIDER_TYPE,
  GeminiThinkingLevel,
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  OpenAiPipelineMode,
  OpenAIReasoningEffort,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ReasoningEffort,
} from "@/types/providers";
import { ANTHROPIC_MODEL_CAPABILITIES } from "@/constants/llm";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
  setLatestProviderModelsSnapshot,
} from "@/lib/modelRegistryStore";
import { getProviderFromModel } from "@/lib/provider";

const ANTHROPIC = PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE;
const OPEN_AI = PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE;

describe("getRoutableProviderModelValue", () => {
  it("qualifies bare Vertex AI Gemini ids", () => {
    expect(
      getRoutableProviderModelValue(
        PROVIDER_TYPE.VERTEX_AI,
        PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
      ),
    ).toBe(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO);
  });

  it("keeps already-qualified Vertex AI values unchanged", () => {
    expect(
      getRoutableProviderModelValue(
        PROVIDER_TYPE.VERTEX_AI,
        PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
      ),
    ).toBe(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO);
  });

  it("keeps non-Vertex provider values unchanged", () => {
    expect(
      getRoutableProviderModelValue(
        PROVIDER_TYPE.GEMINI,
        PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
      ),
    ).toBe(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO);
  });
});

describe("supportsSamplingParams", () => {
  it("returns true for an empty model selector", () => {
    expect(supportsSamplingParams("")).toBe(true);
    expect(supportsSamplingParams(undefined)).toBe(true);
  });

  it("returns true for any model not flagged in ANTHROPIC_MODEL_CAPABILITIES", () => {
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6)).toBe(
      true,
    );
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6)).toBe(
      true,
    );
    expect(
      supportsSamplingParams("never-seen-model" as PROVIDER_MODEL_TYPE),
    ).toBe(true);
  });

  it("returns false for Claude Opus 4.7", () => {
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7)).toBe(
      false,
    );
  });

  it("returns false for Claude Sonnet 5 and Fable 5", () => {
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5)).toBe(
      false,
    );
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_FABLE_5)).toBe(
      false,
    );
  });

  it("returns false for Claude Opus 5", () => {
    expect(supportsSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5)).toBe(
      false,
    );
  });

  // OpenRouter dots the version, sometimes drops the release date and sometimes appends a variant;
  // Bedrock adds a region and an inference profile. The same model must answer the same either way.
  it.each([
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_7, false],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_FABLE_5_1, false],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_FABLE_5_1_BATCH, false],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_6, true],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_6_FAST, true],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_5, true],
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_HAIKU_4_5, true],
    ["us.anthropic.claude-sonnet-4-5-20250929-v1:0", true],
    ["us.anthropic.claude-sonnet-5-20250101-v1:0", false],
  ])("reads %s the same as the id it decorates", (model, expected) => {
    expect(supportsSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(expected);
  });

  // The backend trims before classifying, so a pasted id with stray whitespace must not be read as
  // a different model on the two sides — the panel would offer a control the request then drops.
  // Anthropic names models claude-<family>-<version>. A name that matches no family Anthropic ships
  // is someone's own deployment name, and says nothing about which Claude is behind it, so it keeps
  // the params set on it.
  it.each([
    "claude-prod",
    "claude-internal-v3",
    "custom-llm/gw/my-claude-prod",
    "claude-30-future",
    "claude-future-99",
  ])("treats %s as a deployment name, not an Anthropic id", (model) => {
    expect(supportsSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(true);
  });

  // A floating alias has to read as the model it resolves to, so the families cannot share one
  // answer — and claude-haiku-latest must agree with claude-haiku-4-5 under its own id.
  it.each([
    ["~anthropic/claude-haiku-latest", true],
    ["~anthropic/claude-opus-latest", false],
    ["~anthropic/claude-sonnet-latest", false],
    ["~anthropic/claude-fable-latest", false],
  ])("reads %s as its family's newest member", (model, expected) => {
    expect(supportsSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(expected);
  });

  it.each([
    // The generations that predate the constraint, incl. surviving the -v1 strip.
    ["anthropic/claude-3.5-sonnet", true],
    ["anthropic.claude-v2:1", true],
    ["anthropic.claude-instant-v1", true],
    // A numeric segment is the next version, not a variant of claude-sonnet-4-6.
    ["claude-sonnet-4-6-1", false],
    ["anthropic/claude-opus-4.6-fast", true],
  ])("classifies %s by generation and segment boundary", (model, expected) => {
    expect(supportsSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(expected);
  });

  it("ignores surrounding whitespace, as the backend does", () => {
    expect(
      supportsSamplingParams("  claude-opus-4-7  " as PROVIDER_MODEL_TYPE),
    ).toBe(false);
    expect(
      supportsSamplingParams("  claude-opus-4-6  " as PROVIDER_MODEL_TYPE),
    ).toBe(true);
  });
});

describe("updateProviderConfig — Anthropic", () => {
  it("retains temperature and topP when switching into Opus 4.7, which rejects them", () => {
    // Kept in the config, omitted from the request: Opus 4.7 hides both sliders, and dropping the
    // values here is what used to lose them for good once the user picked a model that takes them.
    const config: LLMAnthropicConfigsType = {
      temperature: 0.5,
      topP: 0.9,
      maxCompletionTokens: 4000,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      provider: ANTHROPIC,
    });
    expect(result?.temperature).toBe(0.5);
    expect(result?.topP).toBe(0.9);
    expect(result?.maxCompletionTokens).toBe(4000);

    const request = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      result as unknown as Record<string, unknown>,
    );
    expect(request.temperature).toBeUndefined();
    expect(request.topP).toBeUndefined();
  });

  it("keeps temperature when switching into Opus 4.6", () => {
    const config: LLMAnthropicConfigsType = {
      temperature: 0.5,
      maxCompletionTokens: 4000,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      provider: ANTHROPIC,
    });
    expect(result?.temperature).toBe(0.5);
  });

  it("drops thinkingEffort when switching to a model with no thinking-effort dropdown", () => {
    const config: LLMAnthropicConfigsType = {
      maxCompletionTokens: 4000,
      thinkingEffort: "high",
    };
    const result = updateProviderConfig(config, {
      // Haiku 4.5 has no thinking effort options in ANTHROPIC_MODEL_CAPABILITIES
      model: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      provider: ANTHROPIC,
    });
    expect(result?.thinkingEffort).toBeUndefined();
  });

  it("returns the same reference when no changes are needed", () => {
    const config: LLMAnthropicConfigsType = {
      temperature: 0.7,
      maxCompletionTokens: 4000,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      provider: ANTHROPIC,
    });
    expect(result).toBe(config);
  });
});

describe("supportsOpenAIReasoningEffort", () => {
  it("returns false for an empty or unknown model", () => {
    expect(supportsOpenAIReasoningEffort("")).toBe(false);
    expect(supportsOpenAIReasoningEffort(undefined)).toBe(false);
    expect(
      supportsOpenAIReasoningEffort("never-seen" as PROVIDER_MODEL_TYPE),
    ).toBe(false);
  });

  it("returns true for reasoning models that have an effort option list", () => {
    expect(supportsOpenAIReasoningEffort(PROVIDER_MODEL_TYPE.GPT_O3)).toBe(
      true,
    );
    expect(supportsOpenAIReasoningEffort(PROVIDER_MODEL_TYPE.GPT_5)).toBe(true);
    expect(supportsOpenAIReasoningEffort(PROVIDER_MODEL_TYPE.GPT_5_1)).toBe(
      true,
    );
  });

  it("returns false for o1-mini (reasoning model that rejects the param)", () => {
    expect(supportsOpenAIReasoningEffort(PROVIDER_MODEL_TYPE.GPT_O1_MINI)).toBe(
      false,
    );
  });

  it("returns false for non-reasoning OpenAI models", () => {
    expect(supportsOpenAIReasoningEffort(PROVIDER_MODEL_TYPE.GPT_4O)).toBe(
      false,
    );
  });
});

describe("getOpenAIReasoningEffortOptions", () => {
  it("returns o-series options for o3", () => {
    const opts = getOpenAIReasoningEffortOptions(PROVIDER_MODEL_TYPE.GPT_O3);
    expect(opts.map((o) => o.value)).toEqual(["low", "medium", "high"]);
  });

  it("returns gpt-5 options including minimal", () => {
    const opts = getOpenAIReasoningEffortOptions(PROVIDER_MODEL_TYPE.GPT_5);
    expect(opts.map((o) => o.value)).toEqual([
      "minimal",
      "low",
      "medium",
      "high",
    ]);
  });

  it("returns gpt-5.1 options with none replacing minimal", () => {
    const opts = getOpenAIReasoningEffortOptions(PROVIDER_MODEL_TYPE.GPT_5_1);
    expect(opts.map((o) => o.value)).toEqual(["none", "low", "medium", "high"]);
  });

  it("returns empty array for o1-mini and non-reasoning models", () => {
    expect(
      getOpenAIReasoningEffortOptions(PROVIDER_MODEL_TYPE.GPT_O1_MINI),
    ).toEqual([]);
    expect(getOpenAIReasoningEffortOptions(PROVIDER_MODEL_TYPE.GPT_4O)).toEqual(
      [],
    );
  });
});

describe("updateProviderConfig — OpenAI", () => {
  it("keeps temperature when switching into a reasoning model, which does not take one", () => {
    // o3 accepts only its own default temperature, so there is nothing to legalise here: the panel
    // offers no slider and the request omits the field. Rewriting the value would lose the user's
    // choice for whenever they pick a model that does take one.
    const config: LLMOpenAIConfigsType = {
      temperature: 0,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_O3,
      provider: OPEN_AI,
    });
    expect(result?.temperature).toBe(0);
    expect(
      sanitizeConfigForRequest(
        PROVIDER_MODEL_TYPE.GPT_O3,
        result as unknown as Record<string, unknown>,
      ).temperature,
    ).toBeUndefined();
  });

  it("leaves the config untouched on a reasoning model that already has temperature 1", () => {
    // OpenAI rejects any topP value on reasoning models — the constraint is the parameter's
    // presence, not its value — but that is the request builder's job to enforce, so nothing here
    // needs changing and the reference survives.
    const config: LLMOpenAIConfigsType = {
      temperature: 1,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_O3,
      provider: OPEN_AI,
    });
    expect(result).toBe(config);
    expect(
      sanitizeConfigForRequest(
        PROVIDER_MODEL_TYPE.GPT_O3,
        result as unknown as Record<string, unknown>,
      ).topP,
    ).toBeUndefined();
  });

  it("coerces invalid reasoningEffort to high when switching into a model that doesn't support it", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 1,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
      reasoningEffort: "minimal", // o3 doesn't accept minimal
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_O3,
      provider: OPEN_AI,
    });
    expect(result?.reasoningEffort).toBe("high");
  });

  it("coerces xhigh to high when switching from gpt-5.1 (where xhigh isn't allowed)", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 1,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
      reasoningEffort: "xhigh",
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_5_1,
      provider: OPEN_AI,
    });
    expect(result?.reasoningEffort).toBe("high");
  });

  it("keeps a valid reasoningEffort across reasoning-model switches", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 1,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
      reasoningEffort: "medium",
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_5,
      provider: OPEN_AI,
    });
    expect(result?.reasoningEffort).toBe("medium");
  });

  it("drops reasoningEffort when switching to o1-mini (rejects the param)", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 1,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
      reasoningEffort: "high",
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_O1_MINI,
      provider: OPEN_AI,
    });
    expect(result?.reasoningEffort).toBeUndefined();
  });

  it("drops reasoningEffort when switching to a non-reasoning OpenAI model", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 0,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
      reasoningEffort: "high",
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_4O,
      provider: OPEN_AI,
    });
    expect(result?.reasoningEffort).toBeUndefined();
  });

  it("returns the same reference when no changes are needed", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 0.7,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_4O,
      provider: OPEN_AI,
    });
    expect(result).toBe(config);
  });

  it("retains both sampling params when switching into a reasoning OpenAI model", () => {
    // Both are kept so gpt-4o -> gpt-5.5 -> gpt-4o gives the sliders back with the user's own
    // values; the request builder is what keeps them off a gpt-5.5 call.
    const config: LLMOpenAIConfigsType = {
      temperature: 0.7,
      maxCompletionTokens: 4000,
      topP: 0.9,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      provider: OPEN_AI,
    });
    expect(result).toBe(config);

    const request = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GPT_5_5,
      result as unknown as Record<string, unknown>,
    );
    expect(request.topP).toBeUndefined();
    expect(request.temperature).toBeUndefined();
  });

  it("keeps topP when switching to a non-reasoning OpenAI model", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 0.5,
      maxCompletionTokens: 4000,
      topP: 0.9,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_4O,
      provider: OPEN_AI,
    });
    expect(result?.topP).toBe(0.9);
  });
});

describe("sanitizeConfigForRequest", () => {
  it("drops topP when both temperature and topP are set on an Anthropic model", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      {
        temperature: 0.7,
        topP: 0.9,
        maxCompletionTokens: 4000,
      },
    );
    expect(result.temperature).toBe(0.7);
    expect(result.topP).toBeUndefined();
  });

  it("keeps topP when temperature is not set", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      {
        topP: 0.9,
        maxCompletionTokens: 4000,
      },
    );
    expect(result.topP).toBe(0.9);
  });

  it("substitutes default maxCompletionTokens for Anthropic when missing", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      {
        throttling: 0,
      },
    );
    expect(result.maxCompletionTokens).toBe(4000);
  });

  it("does not touch maxCompletionTokens when already set", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      {
        maxCompletionTokens: 64000,
      },
    );
    expect(result.maxCompletionTokens).toBe(64000);
  });

  it("does not apply Anthropic rules to non-Anthropic models", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_4O, {
      temperature: 0.7,
      topP: 0.9,
    });
    expect(result.topP).toBe(0.9);
    expect(result.maxCompletionTokens).toBeUndefined();
  });

  it("strips temperature and topP for models that reject sampling params", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      {
        temperature: 0.5,
        topP: 0.9,
        maxCompletionTokens: 4000,
      },
    );
    expect(result.temperature).toBeUndefined();
    expect(result.topP).toBeUndefined();
  });

  it("returns the original object when model is empty", () => {
    const configs = { temperature: 0.5 };
    expect(sanitizeConfigForRequest("", configs)).toBe(configs);
  });

  it("strips reasoningEffort for OpenAI models that don't support it", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_4O, {
      reasoningEffort: "high",
      temperature: 0.5,
    });
    expect(result.reasoningEffort).toBeUndefined();
  });

  it("strips reasoningEffort for o1-mini (reasoning model that rejects the param)", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_O1_MINI, {
      reasoningEffort: "high",
    });
    expect(result.reasoningEffort).toBeUndefined();
  });

  it("replaces an unsupported reasoningEffort value (xhigh on gpt-5.1)", () => {
    // Dropping it left the dropdown showing "High" while the provider applied its own
    // default. gpt-5.1 offers high, so that is what the panel shows and what the request carries.
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5_1, {
      reasoningEffort: "xhigh",
    });
    expect(result.reasoningEffort).toBe("high");
  });

  it("keeps a valid reasoningEffort value for the model", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5, {
      reasoningEffort: "minimal",
    });
    expect(result.reasoningEffort).toBe("minimal");
  });

  it("keeps xhigh for gpt-5.5", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5_5, {
      reasoningEffort: "xhigh",
    });
    expect(result.reasoningEffort).toBe("xhigh");
  });

  it("drops an OpenAI-only reasoningEffort from an Anthropic request", () => {
    // Anthropic takes thinkingEffort, not reasoning_effort. Only a config left over from before a
    // provider change carries one, and passing it on would be junk on the wire.
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      {
        reasoningEffort: "high",
      },
    );
    expect(result.reasoningEffort).toBeUndefined();
    expect(result.thinkingEffort).toBeUndefined();
  });

  it("strips both sampling params for OpenAI reasoning models", () => {
    // gpt-5.5 returns 400 if top_p is in the request, and accepts only its own default temperature.
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5_5, {
      temperature: 0.3,
      topP: 0.9,
    });
    expect(result.topP).toBeUndefined();
    expect(result.temperature).toBeUndefined();
  });

  it("keeps topP for non-reasoning OpenAI models", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_4O, {
      topP: 0.9,
    });
    expect(result.topP).toBe(0.9);
  });
});

describe("penalties on an OpenAI key set to the Responses API", () => {
  const STORED = {
    temperature: 0.4,
    frequencyPenalty: 0.5,
    presencePenalty: 0.3,
  };

  const sentPenalties = (
    model: PROVIDER_MODEL_TYPE,
    mode?: OpenAiPipelineMode,
  ) => {
    const { frequencyPenalty, presencePenalty } = sanitizeConfigForRequest(
      model,
      STORED,
      mode,
    );
    return { frequencyPenalty, presencePenalty };
  };

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("leaves both out of a non-reasoning model's request and keeps the temperature", () => {
    expect(
      supportsPenaltyParams(PROVIDER_MODEL_TYPE.GPT_4O, "responses_api"),
    ).toBe(false);
    expect(
      sanitizeConfigForRequest(
        PROVIDER_MODEL_TYPE.GPT_4O,
        STORED,
        "responses_api",
      ),
    ).toEqual({ temperature: 0.4 });
  });

  it("keeps both in the stored config, so moving the key back restores them", () => {
    sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GPT_4O,
      STORED,
      "responses_api",
    );

    expect(STORED).toEqual({
      temperature: 0.4,
      frequencyPenalty: 0.5,
      presencePenalty: 0.3,
    });
  });

  it.each<OpenAiPipelineMode | undefined>([undefined, "chat_completions_api"])(
    "sends both when the mode is %s",
    (mode) => {
      expect(supportsPenaltyParams(PROVIDER_MODEL_TYPE.GPT_4O, mode)).toBe(
        true,
      );
      expect(sentPenalties(PROVIDER_MODEL_TYPE.GPT_4O, mode)).toEqual({
        frequencyPenalty: 0.5,
        presencePenalty: 0.3,
      });
    },
  );

  it("keeps both for models that never reach the OpenAI key", () => {
    const customId = "custom-llm/my-gateway/gpt-4o" as PROVIDER_MODEL_TYPE;
    setLatestProviderModelsSnapshot({
      ...getLatestProviderModelsSnapshot(),
      "custom-llm:my-gateway": [{ value: customId, label: "gpt-4o" }],
    });

    for (const model of [customId, PROVIDER_MODEL_TYPE.OPENAI_GPT_4O]) {
      expect(sentPenalties(model, "responses_api")).toEqual({
        frequencyPenalty: 0.5,
        presencePenalty: 0.3,
      });
    }
  });
});

describe("Gemini thinking level", () => {
  it("is supported by the Gemini 2.5 family, including Flash Lite", () => {
    expect(
      supportsGeminiThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE),
    ).toBe(true);
    expect(
      supportsGeminiThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO),
    ).toBe(true);
    expect(
      supportsGeminiThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH),
    ).toBe(true);
  });

  it("is supported by the Vertex Gemini 2.5 family", () => {
    expect(
      supportsVertexAIThinkingLevel(
        PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
      ),
    ).toBe(true);
    expect(
      supportsVertexAIThinkingLevel(
        PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
      ),
    ).toBe(true);
  });

  it("keeps the Gemini 3 models supported", () => {
    expect(supportsGeminiThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_PRO)).toBe(
      true,
    );
    expect(
      supportsVertexAIThinkingLevel(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO),
    ).toBe(true);
  });

  it("covers the newer Flash models on both providers", () => {
    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE,
      PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE,
    ]) {
      expect(supportsGeminiThinkingLevel(model)).toBe(true);
      expect(supportsVertexAIThinkingLevel(model)).toBe(false);
    }

    for (const model of [
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_6_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_FLASH_PREVIEW,
    ]) {
      expect(supportsVertexAIThinkingLevel(model)).toBe(true);
      expect(supportsGeminiThinkingLevel(model)).toBe(false);
    }
  });

  // Per Google's support table; the sets genuinely differ per model.
  it("offers each model only the levels it documents", () => {
    const values = (m: PROVIDER_MODEL_TYPE) =>
      getThinkingLevelOptions(m).map((o) => o.value);

    // 3.8 and 3.7 Flash have no "minimal".
    expect(values(PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH)).toEqual([
      "low",
      "medium",
      "high",
    ]);
    expect(values(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH)).toEqual([
      "low",
      "medium",
      "high",
    ]);
    expect(values(PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH)).toEqual([
      "low",
      "medium",
      "high",
    ]);
    expect(values(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH)).toEqual([
      "low",
      "medium",
      "high",
    ]);
    // 3.6/3.5 Flash have all four.
    expect(values(PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH)).toEqual([
      "minimal",
      "low",
      "medium",
      "high",
    ]);
    // 3.1 Flash Lite has all four, plus "none" because it does not think by default. Only the
    // separate -image model is limited to minimal and high.
    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE,
      PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE_PREVIEW,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE_PREVIEW,
    ]) {
      expect(values(model), model).toEqual([
        "none",
        "minimal",
        "low",
        "medium",
        "high",
      ]);
    }
    // Gemini 3 Pro: low and high only.
    expect(values(PROVIDER_MODEL_TYPE.GEMINI_3_PRO)).toEqual(["low", "high"]);
  });

  it("preselects each model's own documented default", () => {
    expect(getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH)).toBe(
      "medium",
    );
    expect(
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH),
    ).toBe("medium");
    expect(getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH)).toBe(
      "medium",
    );
    expect(
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH),
    ).toBe("medium");
    // Flash Lite is the exception: measured against the live API it does not think by default, so it
    // preselects "none" rather than the "minimal" Google's docs table claims.
    expect(
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE),
    ).toBe("none");
    expect(
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE),
    ).toBe("none");
  });

  it("only ever preselects a level the model actually offers", () => {
    for (const model of Object.values(PROVIDER_MODEL_TYPE)) {
      const options = getThinkingLevelOptions(model);
      if (options.length === 0) continue;

      expect(
        options.map((o) => o.value),
        `default for ${model} must be offered`,
      ).toContain(getDefaultThinkingLevel(model));
    }
  });

  it("is not offered for models without thinking support", () => {
    expect(supportsGeminiThinkingLevel(PROVIDER_MODEL_TYPE.GPT_4O)).toBe(false);
    expect(getThinkingLevelOptions(PROVIDER_MODEL_TYPE.GPT_4O)).toEqual([]);
  });

  it("offers an off option for the 2.5 family so thinking can be disabled again", () => {
    const values = getThinkingLevelOptions(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
    ).map((o) => o.value);

    expect(values).toContain("off");
  });

  it("does not offer off for Gemini 3, which cannot disable thinking", () => {
    const values = getThinkingLevelOptions(
      PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
    ).map((o) => o.value);

    expect(values).not.toContain("off");
  });

  it("does not offer off for 2.5 Pro, which cannot disable thinking either", () => {
    expect(
      getThinkingLevelOptions(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO).map(
        (o) => o.value,
      ),
    ).toEqual(["auto", "low", "medium", "high"]);
    expect(
      getThinkingLevelOptions(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO).map(
        (o) => o.value,
      ),
    ).not.toContain("off");
  });

  it("defaults Flash Lite to off, matching Google's own default", () => {
    expect(
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE),
    ).toBe("off");
    expect(
      getDefaultThinkingLevel(
        PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
      ),
    ).toBe("off");
  });

  // Pre-Gemini-3 models take a numeric budget whose documented default is dynamic, so they lead with
  // "auto" — pinning `high` would silently triple the thinking budget over Google's own default.
  it("defaults pre-Gemini-3 models to auto", () => {
    expect(getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO)).toBe(
      "auto",
    );
    expect(getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH)).toBe(
      "auto",
    );
    expect(
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH),
    ).toBe("auto");
  });

  it("still defaults Gemini 3 Pro to high", () => {
    expect(getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_PRO)).toBe(
      "high",
    );
  });

  it("offers auto only for pre-Gemini-3 models", () => {
    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
    ]) {
      expect(
        getThinkingLevelOptions(model).map((o) => o.value),
        `${model} should offer auto`,
      ).toContain("auto");
    }

    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
      PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH,
    ]) {
      expect(
        getThinkingLevelOptions(model).map((o) => o.value),
        `${model} should not offer auto`,
      ).not.toContain("auto");
    }
  });

  // "auto" is the absence of a setting, so it must not reach custom_parameters.
  it("sends no thinking block for auto, letting the model decide", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {
        thinkingLevel: "auto",
      }).custom_parameters,
    ).toBeUndefined();

    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {
        temperature: 0,
      }).custom_parameters,
    ).toBeUndefined();
  });
});

describe("sanitizeConfigForRequest — Gemini thinking", () => {
  it("nests the level under custom_parameters, since flat fields are dropped by the backend", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      { thinkingLevel: "low" },
    );

    expect(result.thinkingLevel).toBeUndefined();
    expect(result.custom_parameters).toEqual({ thinking: { level: "low" } });
  });

  it("does the same for Vertex models", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
      { thinkingLevel: "high" },
    );

    expect(result.custom_parameters).toEqual({ thinking: { level: "high" } });
  });

  it("forwards an off level so thinking can be turned back off", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      { thinkingLevel: "off" },
    );

    expect(result.custom_parameters).toEqual({ thinking: { level: "off" } });
  });

  it("preserves unrelated custom parameters", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
      { thinkingLevel: "medium", custom_parameters: { foo: "bar" } },
    );

    expect(result.custom_parameters).toEqual({
      foo: "bar",
      thinking: { level: "medium" },
    });
  });

  // A prompt persisted before the level control existed has no thinkingLevel, and the playground
  // only reconciles configs on a model change — so nothing fills it in. The dropdown still displays
  // the model default, so the request has to send it or the two disagree.
  it("sends the model's default for a prompt persisted without a level", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, {
        temperature: 0,
      }).custom_parameters,
    ).toEqual({ thinking: { level: "off" } });

    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH, {
        temperature: 0,
      }).custom_parameters,
    ).toEqual({ thinking: { level: "medium" } });
  });

  // A caller that persists the sanitized output and feeds it back has no flat thinkingLevel — the
  // optimizer reloads a saved run's parameters blob wholesale. Substituting the model default there
  // silently reset the user's saved choice on every re-run.
  it("honours a level already nested under custom_parameters", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH, {
        custom_parameters: { thinking: { level: "low" } },
      }).custom_parameters,
    ).toEqual({ thinking: { level: "low" } });
  });

  it("prefers the flat level over a nested one", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH, {
        thinkingLevel: "high",
        custom_parameters: { thinking: { level: "low" } },
      }).custom_parameters,
    ).toEqual({ thinking: { level: "high" } });
  });

  // The Flash Lite models do not think by default, so their preselected level must not switch
  // thinking on — that regressed latency ~2x for a customer (OPIK-8102 follow-up).
  it("defaults the Flash Lite models to none, which sends nothing", () => {
    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
      PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
    ]) {
      expect(getDefaultThinkingLevel(model), `default for ${model}`).toBe(
        "none",
      );
      expect(
        getThinkingLevelOptions(model).map((o) => o.value),
        `${model} should offer none`,
      ).toContain("none");
      expect(
        sanitizeConfigForRequest(model, { temperature: 0 }).custom_parameters,
        `${model} should send no thinking block by default`,
      ).toBeUndefined();
    }
  });

  // "none" must REMOVE a persisted block, not merely decline to add one, or the level saved before
  // this change keeps being sent and the model keeps thinking.
  it("clears a persisted thinking block when none is selected", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, {
        thinkingLevel: "none",
        custom_parameters: {
          thinking: { level: "minimal" },
          unrelated: "keep",
        },
      }).custom_parameters,
    ).toEqual({ unrelated: "keep" });
  });

  it("drops custom_parameters entirely when none leaves it empty", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, {
        thinkingLevel: "none",
        custom_parameters: { thinking: { level: "minimal" } },
      }).custom_parameters,
    ).toBeUndefined();
  });

  // A budget set through the API outranks any level server-side, so keeping it under Auto would
  // pin how much the model thinks while the panel says the model decides.
  it.each([
    PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
    PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
  ])("drops a persisted budget for auto on %s", (model) => {
    for (const configs of [
      {
        thinkingLevel: "auto",
        custom_parameters: { thinking: { budget_tokens: 4096 } },
      },
      { custom_parameters: { thinking: { budget_tokens: 4096 } } },
    ]) {
      expect(
        sanitizeConfigForRequest(model, configs).custom_parameters,
      ).toBeUndefined();
    }
  });

  it("sends a low or medium level on 3.1 Flash Lite instead of resetting it to none", () => {
    for (const thinkingLevel of ["low", "medium"] as const) {
      expect(
        sanitizeConfigForRequest(
          PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
          { thinkingLevel },
        ).custom_parameters,
      ).toEqual({ thinking: { level: thinkingLevel } });
    }
  });

  it("sends no thinking block for an explicit none", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, {
        thinkingLevel: "none",
      }).custom_parameters,
    ).toBeUndefined();
  });

  // A thinking-by-default model keeps its level: none is only for the models that ship without it.
  it("does not offer none to models that think by default", () => {
    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
    ]) {
      expect(
        getThinkingLevelOptions(model).map((o) => o.value),
        `${model} should not offer none`,
      ).not.toContain("none");
    }
  });

  it("adds no thinking block for models without a level control", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH, {
        temperature: 0,
      }).custom_parameters,
    ).toBeUndefined();
  });

  // A level the model doesn't offer resolves to that model's default, same as a missing one, so the
  // dropdown and the request agree. Sending nothing would leave them disagreeing.
  it("replaces a level the model does not accept with the model default", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_3_PRO, {
      thinkingLevel: "off",
    });

    expect(result.thinkingLevel).toBeUndefined();
    expect(result.custom_parameters).toEqual({ thinking: { level: "high" } });
  });

  // Gemini 2.5's default is "auto", which sends nothing at all.
  it("sends nothing when a rejected level falls back to an auto default", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      { thinkingLevel: "minimal" },
    );

    expect(result.custom_parameters).toBeUndefined();
  });

  // An explicit budget outranks the level server-side, so "off" has to clear it.
  it("clears a persisted budget when off is selected", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      {
        thinkingLevel: "off",
        custom_parameters: {
          thinking: { budget_tokens: 4096, include_thoughts: true },
        },
      },
    );

    expect(result.custom_parameters).toEqual({
      thinking: { include_thoughts: true, level: "off" },
    });
  });

  it("drops the level for models without thinking support", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
      { thinkingLevel: "high" },
    );

    expect(result.thinkingLevel).toBeUndefined();
    expect(result.custom_parameters).toBeUndefined();
  });

  it("merges the level into an existing thinking block, keeping its other fields", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      {
        thinkingLevel: "low",
        custom_parameters: {
          thinking: { budget_tokens: 4096, include_thoughts: true },
        },
      },
    );

    expect(result.custom_parameters).toEqual({
      thinking: { budget_tokens: 4096, include_thoughts: true, level: "low" },
    });
  });
});

describe("updateProviderConfig — Gemini thinking level", () => {
  const GEMINI = PROVIDER_TYPE.GEMINI as COMPOSED_PROVIDER_TYPE;

  it("coerces a level the new model does not accept to that model's default", () => {
    const next = updateProviderConfig(
      { thinkingLevel: "off" as const },
      { model: PROVIDER_MODEL_TYPE.GEMINI_3_PRO, provider: GEMINI },
    );

    expect(next?.thinkingLevel).toBe("high");
  });

  it("drops the level for a model without thinking support", () => {
    const next = updateProviderConfig(
      { thinkingLevel: "high" as const },
      { model: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH, provider: GEMINI },
    );

    expect(next?.thinkingLevel).toBeUndefined();
  });

  it("fills in the model's default when no level is set, so the shown value is the sent value", () => {
    const empty: { thinkingLevel?: GeminiThinkingLevel } = {};

    expect(
      updateProviderConfig(empty, {
        model: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
        provider: GEMINI,
      })?.thinkingLevel,
    ).toBe("off");
    expect(
      updateProviderConfig(empty, {
        model: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
        provider: GEMINI,
      })?.thinkingLevel,
    ).toBe("auto");
  });

  it("leaves a level the model accepts untouched", () => {
    const config = { thinkingLevel: "off" as const };
    const next = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      provider: GEMINI,
    });

    expect(next).toBe(config);
  });
});

describe("sampling params survive a model round trip", () => {
  it("keeps topP across gpt-4o-mini -> gpt-5.5 -> gpt-4o-mini", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 0,
      maxCompletionTokens: 4000,
      topP: 0.9,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };

    const onReasoning = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      provider: OPEN_AI,
    });
    const back = updateProviderConfig(onReasoning, {
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      provider: OPEN_AI,
    });

    expect(back?.topP).toBe(0.9);
  });

  it("keeps temperature across sonnet-4.6 -> sonnet-5 -> sonnet-4.6", () => {
    const config: LLMAnthropicConfigsType = {
      temperature: 0.7,
      maxCompletionTokens: 4000,
    };

    const onSonnet5 = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
      provider: ANTHROPIC,
    });
    const back = updateProviderConfig(onSonnet5, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
      provider: ANTHROPIC,
    });

    expect(back?.temperature).toBe(0.7);
  });

  it("keeps temperature across gpt-4o-mini -> gpt-5.5 -> gpt-4o-mini", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 0.3,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };

    const onReasoning = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      provider: OPEN_AI,
    });
    const back = updateProviderConfig(onReasoning, {
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      provider: OPEN_AI,
    });

    expect(back?.temperature).toBe(0.3);
  });

  it("still omits the retained value from the wire while the model rejects it", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        temperature: 0.7,
        maxCompletionTokens: 4000,
      }).temperature,
    ).toBeUndefined();

    const onReasoningModel = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.GPT_5_5,
      { temperature: 0.3, topP: 0.9 },
    );
    expect(onReasoningModel.topP).toBeUndefined();
    expect(onReasoningModel.temperature).toBeUndefined();
  });
});

describe("resolveSamplingParams", () => {
  it("re-establishes the temperature/topP pair for an Anthropic config that has neither", () => {
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {}),
    ).toEqual({ temperature: 0 });
  });

  it("omits both for an Anthropic model that rejects sampling params", () => {
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        temperature: 0.7,
        topP: 0.9,
      }),
    ).toEqual({});
  });

  it("keeps temperature and drops topP when an Anthropic config carries both", () => {
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        temperature: 0.7,
        topP: 0.9,
      }),
    ).toEqual({ temperature: 0.7 });
  });

  it("keeps topP alone when the user cleared temperature", () => {
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        topP: 0.9,
      }),
    ).toEqual({ topP: 0.9 });
  });

  it("does not invent a topP the config has no value for", () => {
    // The same panels serve the LLM judge, whose rule stores no topP. Putting a parameter back for
    // a surface that owns it is that surface's job — restoreMissingConfigKeys for the playground.
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.GPT_4O_MINI, {
        temperature: 0,
      }).topP,
    ).toBeUndefined();
  });

  it("omits both for OpenAI reasoning models, which take neither", () => {
    // top_p is rejected outright and temperature accepts only the provider's own default, so
    // neither is tunable and the panel offers no slider for either.
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.GPT_5_5, {
        temperature: 0.3,
        topP: 0.9,
      }),
    ).toEqual({});
  });

  it("passes other providers' values through untouched", () => {
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO, {
        temperature: 0.3,
        topP: 0.8,
      }),
    ).toEqual({ temperature: 0.3, topP: 0.8 });
  });
});

describe("every model with an Anthropic capability row", () => {
  it("never resolves to both temperature and topP, whatever it routes to", () => {
    // This replaces a narrower guard that required every such model to route to the Anthropic
    // provider. Some capability rows deliberately cover ids the frontend registry does not offer
    // (dated variants reachable only through the API or a proxy), and the rule no longer depends on
    // routing: what must hold is that Anthropic never receives the pair.
    for (const model of Object.keys(
      ANTHROPIC_MODEL_CAPABILITIES,
    ) as PROVIDER_MODEL_TYPE[]) {
      const resolved = resolveSamplingParams(model, {
        temperature: 0.7,
        topP: 0.9,
      });

      expect(
        resolved.temperature !== undefined && resolved.topP !== undefined,
      ).toBe(false);
    }
  });

  it("omits both for the ones not marked as taking them", () => {
    for (const [model, capabilities] of Object.entries(
      ANTHROPIC_MODEL_CAPABILITIES,
    )) {
      if (capabilities?.supportsSamplingParams) continue;

      expect(
        resolveSamplingParams(model as PROVIDER_MODEL_TYPE, {
          temperature: 0.7,
          topP: 0.9,
        }),
      ).toEqual({});
    }
  });
});

describe("the settings panel and the request agree on sampling params", () => {
  // Both shapes are what an earlier model switch left behind in PLAYGROUND_STATE. The panel reads
  // them through resolveSamplingParams, so the request has to resolve to the same values.
  it("sends the temperature the panel resolves for an Anthropic config that lost both", () => {
    const configs: LLMAnthropicConfigsType = { maxCompletionTokens: 4000 };

    expect(
      sanitizeConfigForRequest(
        PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
        configs as unknown as Record<string, unknown>,
      ).temperature,
    ).toBe(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, configs)
        .temperature,
    );
  });

  it("sends the topP the panel resolves for an OpenAI config that carries one", () => {
    const configs: LLMOpenAIConfigsType = {
      temperature: 0,
      maxCompletionTokens: 4000,
      topP: 0.75,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };

    expect(
      sanitizeConfigForRequest(
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        configs as unknown as Record<string, unknown>,
      ).topP,
    ).toBe(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.GPT_4O_MINI, configs).topP,
    );
  });
});

describe("resolveEffort", () => {
  it("omits reasoningEffort for an OpenAI model with no effort options", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.GPT_4O, { reasoningEffort: "high" }),
    ).toEqual({});
  });

  it("keeps a stored reasoningEffort the model offers", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.GPT_5, { reasoningEffort: "minimal" }),
    ).toEqual({ reasoningEffort: "minimal" });
  });

  it("falls back to high for a reasoningEffort the model does not offer", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.GPT_5_1, { reasoningEffort: "xhigh" }),
    ).toEqual({ reasoningEffort: "high" });
  });

  it("falls back to high when a supporting model has nothing stored", () => {
    // What the dropdown has always displayed. Leaving it unresolved is how the panel came to show
    // "High" while the request carried no reasoning_effort at all.
    expect(resolveEffort(PROVIDER_MODEL_TYPE.GPT_5_5, {})).toEqual({
      reasoningEffort: "high",
    });
  });

  it("omits thinkingEffort for an Anthropic model with no effort options", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, {
        thinkingEffort: "high",
      }),
    ).toEqual({});
  });

  it("passes other providers' values through untouched", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO, {
        reasoningEffort: "low",
        thinkingEffort: "max",
      }),
    ).toEqual({ reasoningEffort: "low", thinkingEffort: "max" });
  });
});

describe("resolveThinkingLevel", () => {
  it.each<[string, PROVIDER_MODEL_TYPE, Record<string, unknown>, string]>([
    [
      "keeps a stored level the model offers",
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      { thinkingLevel: "low" },
      "low",
    ],
    [
      "falls back to the default for a stored level the model does not offer",
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      { thinkingLevel: "minimal" },
      "auto",
    ],
    [
      "does the same on Vertex AI",
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      { thinkingLevel: "minimal" },
      "auto",
    ],
    [
      "keeps minimal on a model that offers it",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      { thinkingLevel: "minimal" },
      "minimal",
    ],
    [
      "falls back to the default when nothing is stored",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      {},
      getDefaultThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_3_FLASH),
    ],
    [
      "reads a level nested under custom_parameters",
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      { custom_parameters: { thinking: { level: "high" } } },
      "high",
    ],
    [
      "lets a flat level win over a nested one",
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      {
        thinkingLevel: "medium",
        custom_parameters: { thinking: { level: "high" } },
      },
      "medium",
    ],
  ])("%s", (_, model, configs, expected) => {
    expect(resolveThinkingLevel(model, configs)).toBe(expected);
  });

  it.each([
    PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
  ])(
    "sends what the dropdown shows when %s does not offer the stored level",
    (model) => {
      const configs = { thinkingLevel: "minimal" as const };

      expect(resolveThinkingLevel(model, configs)).toBe("auto");
      expect(sanitizeConfigForRequest(model, configs)).toEqual({});
    },
  );
});

describe("Auto sends no Gemini thinking level", () => {
  it.each<[string, Record<string, unknown>]>([
    [
      "a flat auto over a nested level",
      {
        thinkingLevel: "auto",
        custom_parameters: { thinking: { level: "high" } },
      },
    ],
    [
      "a nested level the model does not offer",
      { custom_parameters: { thinking: { level: "minimal" } } },
    ],
    [
      "a flat level the model does not offer over a nested one",
      {
        thinkingLevel: "minimal",
        custom_parameters: { thinking: { level: "high" } },
      },
    ],
  ])("drops %s", (_, configs) => {
    for (const model of [
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
    ]) {
      expect(resolveThinkingLevel(model, configs), model).toBe("auto");
      expect(
        sanitizeConfigForRequest(model, configs).custom_parameters,
        model,
      ).toBeUndefined();
    }
  });

  it("drops the budget too, and keeps include_thoughts and the other custom parameters", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {
        thinkingLevel: "auto",
        custom_parameters: {
          thinking: {
            level: "low",
            budget_tokens: 4096,
            include_thoughts: true,
          },
          unrelated: "keep",
        },
      }).custom_parameters,
    ).toEqual({
      thinking: { include_thoughts: true },
      unrelated: "keep",
    });
  });
});

describe("withoutThinkingAmount", () => {
  it.each<[string, unknown, unknown]>([
    ["nothing stored", undefined, undefined],
    [
      "a block holding only a level",
      { thinking: { level: "high" } },
      undefined,
    ],
    [
      "other keys next to the block",
      { thinking: { level: "high" }, seed: 1 },
      { seed: 1 },
    ],
    [
      "a block holding only a budget",
      { thinking: { budget_tokens: 4096 }, seed: 1 },
      { seed: 1 },
    ],
    [
      "a level and a budget next to include_thoughts",
      {
        thinking: {
          level: "low",
          budget_tokens: 4096,
          include_thoughts: false,
        },
      },
      { thinking: { include_thoughts: false } },
    ],
    [
      "a block with neither",
      { thinking: { include_thoughts: true } },
      { thinking: { include_thoughts: true } },
    ],
    [
      "a thinking value that is not an object",
      { thinking: "on" },
      { thinking: "on" },
    ],
  ])("handles %s", (_, customParameters, expected) => {
    expect(withoutThinkingAmount(customParameters)).toEqual(expected);
  });
});

describe("a model switch carries the thinking level the panel showed", () => {
  const GEMINI = PROVIDER_TYPE.GEMINI as COMPOSED_PROVIDER_TYPE;

  it.each<
    [PROVIDER_MODEL_TYPE, GeminiThinkingLevel, PROVIDER_MODEL_TYPE, string]
  >([
    [
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      "minimal",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      "high",
    ],
    [
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      "minimal",
      PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE,
      "none",
    ],
    [
      PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO,
      "minimal",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      "high",
    ],
    [
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      "none",
      PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE,
      "none",
    ],
    [
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      "low",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      "low",
    ],
  ])("%s holding %s, then %s: %s", (from, thinkingLevel, to, expected) => {
    const switched = updateProviderConfig(
      withShownThinkingLevel(from, { thinkingLevel }),
      { model: to, provider: GEMINI },
    );

    expect(switched?.thinkingLevel).toBe(expected);
  });

  it("leaves a model without a level control alone", () => {
    const configs = { temperature: 0.5 };

    expect(
      withShownThinkingLevel(PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH, configs),
    ).toBe(configs);
  });
});

describe("the settings panel and the request agree on effort", () => {
  it("sends the reasoning effort the dropdown displays after switching into a reasoning model", () => {
    const config: LLMOpenAIConfigsType = {
      temperature: 0,
      maxCompletionTokens: 4000,
      topP: 1,
      frequencyPenalty: 0,
      presencePenalty: 0,
    };

    const onReasoning = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      provider: OPEN_AI,
    });

    expect(
      sanitizeConfigForRequest(
        PROVIDER_MODEL_TYPE.GPT_5_5,
        onReasoning as unknown as Record<string, unknown>,
      ).reasoningEffort,
    ).toBe(
      resolveEffort(PROVIDER_MODEL_TYPE.GPT_5_5, onReasoning ?? {})
        .reasoningEffort,
    );
  });
});

describe("Claude sampling exclusivity across providers", () => {
  // The constraint is the model's, not the provider's: Bedrock answers a request carrying both with
  // "temperature and top_p cannot both be specified for this model", and the same Claude models
  // reach us through Bedrock, OpenRouter and OpenAI-compatible proxies under decorated names.
  it.each([
    ["us.anthropic.claude-sonnet-4-5-20250929-v1:0", "Bedrock"],
    ["bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0", "Bedrock via proxy"],
    ["claude-opus-4-6", "an OpenAI-compatible proxy"],
    // Sonnet 5 takes neither, so it belongs to the cases below, not here.
    [PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_6, "OpenRouter"],
  ])("drops topP for %s served by %s", (model) => {
    expect(
      resolveSamplingParams(model as PROVIDER_MODEL_TYPE, {
        temperature: 0.7,
        topP: 0.9,
      }),
    ).toEqual({ temperature: 0.7 });
  });

  it("keeps topP for a Claude model when temperature is not set", () => {
    expect(
      resolveSamplingParams(
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0" as PROVIDER_MODEL_TYPE,
        { topP: 0.9 },
      ),
    ).toEqual({ temperature: undefined, topP: 0.9 });
  });

  it("does not treat a gateway named after Claude as Claude", () => {
    // The custom id carries the gateway in its prefix, so the model itself has to decide.
    expect(
      resolveSamplingParams(
        "custom-llm/claude-gw/mistral-large-2411" as PROVIDER_MODEL_TYPE,
        { temperature: 0.7, topP: 0.9 },
      ),
    ).toEqual({ temperature: 0.7, topP: 0.9 });
  });

  it("still matches a Claude model behind such a gateway", () => {
    expect(
      resolveSamplingParams(
        "custom-llm/claude-gw/claude-opus-4-6" as PROVIDER_MODEL_TYPE,
        { temperature: 0.7, topP: 0.9 },
      ),
    ).toEqual({ temperature: 0.7 });
  });

  // The adaptive-thinking models reject both parameters outright, not merely together, and arrive
  // through the same decorated ids as everything else.
  it.each([
    "custom-llm/gw/claude-sonnet-5",
    "anthropic/claude-sonnet-5",
    "us.anthropic.claude-sonnet-5-20250101-v1:0",
    "custom-llm/gw/claude-opus-4-7",
  ])("omits both for %s, which takes neither", (model) => {
    expect(
      resolveSamplingParams(model as PROVIDER_MODEL_TYPE, {
        temperature: 0.7,
        topP: 0.9,
      }),
    ).toEqual({});
  });

  it("omits a lone temperature for a model that takes neither", () => {
    expect(
      resolveSamplingParams(
        "custom-llm/gw/claude-sonnet-5" as PROVIDER_MODEL_TYPE,
        { temperature: 0.7 },
      ),
    ).toEqual({});
  });

  it("leaves a sampling-capable Claude on the same route alone", () => {
    expect(
      resolveSamplingParams(
        "custom-llm/gw/claude-sonnet-4-6" as PROVIDER_MODEL_TYPE,
        { temperature: 0.7 },
      ),
    ).toEqual({ temperature: 0.7, topP: undefined });
  });

  it("does not classify an id whose model segment is empty", () => {
    // Must agree with the backend, which sees the same id and must not fall back to the gateway.
    expect(
      resolveSamplingParams("custom-llm/claude-gw/" as PROVIDER_MODEL_TYPE, {
        temperature: 0.7,
        topP: 0.9,
      }),
    ).toEqual({ temperature: 0.7, topP: 0.9 });
  });

  it("leaves a non-Claude model on the same provider alone", () => {
    expect(
      resolveSamplingParams("mistral-large-2411" as PROVIDER_MODEL_TYPE, {
        temperature: 0.7,
        topP: 0.9,
      }),
    ).toEqual({ temperature: 0.7, topP: 0.9 });
  });

  it("keeps topP off the request for a Claude model on a non-Anthropic provider", () => {
    expect(
      sanitizeConfigForRequest(
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0" as PROVIDER_MODEL_TYPE,
        { temperature: 0.7, topP: 0.9, maxCompletionTokens: 4000 },
      ),
    ).toMatchObject({ temperature: 0.7, maxCompletionTokens: 4000 });
  });

  it("does not leave topP on the request for a Claude model on a non-Anthropic provider", () => {
    expect(
      sanitizeConfigForRequest(
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0" as PROVIDER_MODEL_TYPE,
        { temperature: 0.7, topP: 0.9 },
      ).topP,
    ).toBeUndefined();
  });
});

describe("OpenAI request contract", () => {
  const FULL_CONFIG: LLMOpenAIConfigsType = {
    temperature: 0.7,
    maxCompletionTokens: 4000,
    topP: 0.9,
    frequencyPenalty: 0.2,
    presencePenalty: 0.1,
    reasoningEffort: "high",
    throttling: 0,
    maxConcurrentRequests: 5,
  };
  const REASONING_REQUEST = {
    maxCompletionTokens: 4000,
    reasoningEffort: "high",
    throttling: 0,
    maxConcurrentRequests: 5,
  };
  const REASONING_REQUEST_WITHOUT_EFFORT = omit(
    REASONING_REQUEST,
    "reasoningEffort",
  );
  const SAMPLING_REQUEST = omit(FULL_CONFIG, "reasoningEffort");

  const NONE_TO_XHIGH: ReasoningEffort[] = [
    "none",
    "low",
    "medium",
    "high",
    "xhigh",
  ];

  const sanitize = (model: PROVIDER_MODEL_TYPE) =>
    sanitizeConfigForRequest(model, {
      ...(FULL_CONFIG as unknown as Record<string, unknown>),
    });

  describe.each<{
    model: PROVIDER_MODEL_TYPE;
    reasoning: boolean;
    effortOptions: ReasoningEffort[];
    request: Record<string, unknown>;
  }>([
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_ASTRA,
      reasoning: true,
      effortOptions: ["low", "medium", "high", "xhigh"],
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_1_SOL,
      reasoning: true,
      effortOptions: ["low", "medium", "high", "xhigh"],
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_SOL,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_LUNA,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_6_LUNA,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_6_SOL,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_6_TERRA,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_4,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_4_MINI,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_4_NANO,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_2,
      reasoning: true,
      effortOptions: NONE_TO_XHIGH,
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_1,
      reasoning: true,
      effortOptions: ["none", "low", "medium", "high"],
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5,
      reasoning: true,
      effortOptions: ["minimal", "low", "medium", "high"],
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_O1,
      reasoning: true,
      effortOptions: ["low", "medium", "high"],
      request: REASONING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_O1_MINI,
      reasoning: true,
      effortOptions: [],
      request: REASONING_REQUEST_WITHOUT_EFFORT,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_CHAT_LATEST,
      reasoning: false,
      effortOptions: [],
      request: SAMPLING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_2_CHAT_LATEST,
      reasoning: false,
      effortOptions: [],
      request: SAMPLING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_3_CHAT_LATEST,
      reasoning: false,
      effortOptions: [],
      request: SAMPLING_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_4O,
      reasoning: false,
      effortOptions: [],
      request: SAMPLING_REQUEST,
    },
  ])("$model", ({ model, reasoning, effortOptions, request }) => {
    it(`is ${reasoning ? "" : "not "}a reasoning model`, () => {
      expect(isReasoningModel(model)).toBe(reasoning);
    });

    it("offers exactly the effort levels Chat Completions accepts", () => {
      expect(
        getOpenAIReasoningEffortOptions(model).map((o) => o.value),
      ).toEqual(effortOptions);
    });

    it("sends exactly the parameters it accepts", () => {
      expect(sanitize(model)).toEqual(request);
    });
  });

  it("labels high without claiming it is the provider default", () => {
    const high = getOpenAIReasoningEffortOptions(
      PROVIDER_MODEL_TYPE.GPT_5,
    ).find((o) => o.value === "high");
    expect(high?.label).toBe("High");
  });

  describe("a model with no capability row", () => {
    const UNLISTED = "gpt-7-test" as PROVIDER_MODEL_TYPE;

    afterEach(() => {
      resetModelRegistryStoreForTesting();
    });

    it("follows the registry when it flags the model as reasoning", () => {
      setLatestModelFlags(
        new Map([[UNLISTED, { reasoning: true, structuredOutput: true }]]),
      );

      expect(isReasoningModel(UNLISTED)).toBe(true);
      expect(getOpenAIReasoningEffortOptions(UNLISTED)).toEqual([]);
      expect(sanitize(UNLISTED)).toEqual(REASONING_REQUEST_WITHOUT_EFFORT);
    });

    it("is not a reasoning model when the registry does not know it", () => {
      expect(isReasoningModel(UNLISTED)).toBe(false);
      expect(sanitize(UNLISTED)).toEqual(SAMPLING_REQUEST);
    });

    it("cannot override an explicit non-reasoning row", () => {
      setLatestModelFlags(
        new Map([
          [
            PROVIDER_MODEL_TYPE.GPT_5_CHAT_LATEST,
            { reasoning: true, structuredOutput: true },
          ],
        ]),
      );

      expect(isReasoningModel(PROVIDER_MODEL_TYPE.GPT_5_CHAT_LATEST)).toBe(
        false,
      );
    });
  });
});

describe("max on the OpenAI Responses API only", () => {
  const NONE_TO_XHIGH: ReasoningEffort[] = [
    "none",
    "low",
    "medium",
    "high",
    "xhigh",
  ];
  const LOW_TO_XHIGH: ReasoningEffort[] = ["low", "medium", "high", "xhigh"];
  const MODES: Array<OpenAiPipelineMode | undefined> = [
    undefined,
    "chat_completions_api",
    "responses_api",
  ];

  const storedMax = (model: PROVIDER_MODEL_TYPE, mode?: OpenAiPipelineMode) =>
    sanitizeConfigForRequest(
      model,
      { maxCompletionTokens: 4000, reasoningEffort: "max" },
      mode,
    ).reasoningEffort;

  const STORED_MAX: LLMOpenAIConfigsType = {
    temperature: 0,
    maxCompletionTokens: 4000,
    topP: 1,
    frequencyPenalty: 0,
    presencePenalty: 0,
    reasoningEffort: "max",
  };

  const switchTo = (model: PROVIDER_MODEL_TYPE, mode?: OpenAiPipelineMode) =>
    updateProviderConfig(STORED_MAX, {
      model,
      provider: OPEN_AI,
      openAiPipelineMode: mode,
    })?.reasoningEffort;

  describe.each<{
    model: PROVIDER_MODEL_TYPE;
    chatCompletions: ReasoningEffort[];
    responsesApi: OpenAIReasoningEffort[];
  }>([
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_6_LUNA,
      chatCompletions: NONE_TO_XHIGH,
      responsesApi: [...NONE_TO_XHIGH, "max"],
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_6_SOL,
      chatCompletions: NONE_TO_XHIGH,
      responsesApi: [...NONE_TO_XHIGH, "max"],
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_6_TERRA,
      chatCompletions: NONE_TO_XHIGH,
      responsesApi: [...NONE_TO_XHIGH, "max"],
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_ASTRA,
      chatCompletions: LOW_TO_XHIGH,
      responsesApi: [...LOW_TO_XHIGH, "max"],
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_SOL,
      chatCompletions: NONE_TO_XHIGH,
      responsesApi: [...NONE_TO_XHIGH, "max"],
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_LUNA,
      chatCompletions: NONE_TO_XHIGH,
      responsesApi: [...NONE_TO_XHIGH, "max"],
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_6_1_SOL,
      chatCompletions: LOW_TO_XHIGH,
      responsesApi: LOW_TO_XHIGH,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_5,
      chatCompletions: NONE_TO_XHIGH,
      responsesApi: NONE_TO_XHIGH,
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_O3,
      chatCompletions: ["low", "medium", "high"],
      responsesApi: ["low", "medium", "high"],
    },
  ])("$model", ({ model, chatCompletions, responsesApi }) => {
    const offersMax = responsesApi.includes("max");

    it.each([undefined, "chat_completions_api" as const])(
      "offers only the Chat Completions levels when the mode is %s",
      (mode) => {
        expect(
          getOpenAIReasoningEffortOptions(model, mode).map((o) => o.value),
        ).toEqual(chatCompletions);
      },
    );

    it("offers the Responses API levels on a Responses API key", () => {
      expect(
        getOpenAIReasoningEffortOptions(model, "responses_api").map(
          (o) => o.value,
        ),
      ).toEqual(responsesApi);
    });

    it("sends a stored max as high unless the key is on the Responses API", () => {
      expect(storedMax(model)).toBe("high");
      expect(storedMax(model, "chat_completions_api")).toBe("high");
      expect(storedMax(model, "responses_api")).toBe(
        offersMax ? "max" : "high",
      );
    });

    it.each(MODES)(
      "shows the same effort the request sends when the mode is %s",
      (mode) => {
        expect(
          resolveEffort(model, { reasoningEffort: "max" }, mode)
            .reasoningEffort,
        ).toBe(storedMax(model, mode));
      },
    );

    it.each(["chat_completions_api", "responses_api"] as const)(
      "keeps a switched-in max only where it is sent, when the mode is %s",
      (mode) => {
        expect(switchTo(model, mode)).toBe(storedMax(model, mode));
      },
    );

    it("keeps a switched-in max while the mode is unknown, if a Responses API key could send it", () => {
      expect(switchTo(model, undefined)).toBe(offersMax ? "max" : "high");
    });
  });

  describe("a model switch made while the provider keys are loading", () => {
    it("keeps the stored max, then coerces it once the key turns out to be on Chat Completions", () => {
      const whileLoading = updateProviderConfig(STORED_MAX, {
        model: PROVIDER_MODEL_TYPE.GPT_5_6_SOL,
        provider: OPEN_AI,
        openAiPipelineMode: undefined,
      });
      expect(whileLoading?.reasoningEffort).toBe("max");

      const onceKnown = updateProviderConfig(whileLoading, {
        model: PROVIDER_MODEL_TYPE.GPT_6_SOL,
        provider: OPEN_AI,
        openAiPipelineMode: "chat_completions_api",
      });
      expect(onceKnown?.reasoningEffort).toBe("high");
    });

    it("still drops the effort for a model that takes none", () => {
      expect(switchTo(PROVIDER_MODEL_TYPE.GPT_4O, undefined)).toBeUndefined();
    });
  });
});

describe("an OpenAI reasoning model reached through a custom gateway", () => {
  const SAMPLING: SamplingParams = { temperature: 0.7, topP: 0.9 };
  const CUSTOM_ID = "custom-llm/my-gateway/gpt-6-astra" as PROVIDER_MODEL_TYPE;

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("keeps penalties and sampling behind a named custom gateway", () => {
    setLatestProviderModelsSnapshot({
      ...getLatestProviderModelsSnapshot(),
      "custom-llm:my-gateway": [{ value: CUSTOM_ID, label: "gpt-6-astra" }],
    });

    // getProviderFromModel ignores the composed `custom-llm:<name>` key, so the id falls back to
    // OpenAI; it keeps its params only because no capability row or registry flag names it.
    expect(getProviderFromModel(CUSTOM_ID)).toBe(PROVIDER_TYPE.OPEN_AI);
    expect(supportsPenaltyParams(CUSTOM_ID)).toBe(true);
    expect(resolveSamplingParams(CUSTOM_ID, SAMPLING)).toEqual(SAMPLING);
  });
});

describe("an OpenAI or Gemini model reached through OpenRouter", () => {
  const SAMPLING: SamplingParams = { temperature: 0.7, topP: 0.9 };

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each<[PROVIDER_MODEL_TYPE, SamplingParams]>([
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_6_ASTRA, {}],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH, {}],
    [PROVIDER_MODEL_TYPE.OPENAI_O3, {}],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW, {}],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW_BATCH, {}],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_FLASH_LITE, {}],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI, SAMPLING],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH, SAMPLING],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_4_31B_IT, SAMPLING],
    // OpenRouter-only names with no native row: left alone rather than guessed.
    [PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH, SAMPLING],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_5_CHAT, SAMPLING],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_OSS_120B, SAMPLING],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS, SAMPLING],
  ])("resolves sampling params on %s to %j", (model, expected) => {
    expect(getProviderFromModel(model)).toBe(PROVIDER_TYPE.OPEN_ROUTER);
    expect(resolveSamplingParams(model, SAMPLING)).toEqual(expected);
  });

  it.each<[PROVIDER_MODEL_TYPE, boolean]>([
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_6_ASTRA, false],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO_BATCH, false],
    [PROVIDER_MODEL_TYPE.OPENAI_O3, false],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI, true],
    [PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH, true],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW, true],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_4_31B_IT, true],
  ])("supports penalties on %s: %s", (model, expected) => {
    expect(supportsPenaltyParams(model)).toBe(expected);
  });

  it("follows the registry for a native id only the registry lists", () => {
    const model = PROVIDER_MODEL_TYPE.OPENAI_O3_PRO;
    expect(resolveSamplingParams(model, SAMPLING)).toEqual(SAMPLING);

    const snapshot = getLatestProviderModelsSnapshot();
    setLatestProviderModelsSnapshot({
      ...snapshot,
      [PROVIDER_TYPE.OPEN_AI]: [
        ...(snapshot[PROVIDER_TYPE.OPEN_AI] ?? []),
        { value: "o3-pro" as PROVIDER_MODEL_TYPE, label: "o3-pro" },
      ],
    });
    setLatestModelFlags(
      new Map([["o3-pro", { reasoning: true, structuredOutput: true }]]),
    );

    expect(resolveSamplingParams(model, SAMPLING)).toEqual({});
    expect(supportsPenaltyParams(model)).toBe(false);
  });

  it("ignores a Responses API pipeline mode, which belongs to the OpenAI key", () => {
    expect(
      supportsPenaltyParams(
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        "responses_api",
      ),
    ).toBe(true);
  });
});

describe("Anthropic request contract", () => {
  const FULL_CONFIG: LLMAnthropicConfigsType = {
    temperature: 0.7,
    maxCompletionTokens: 4000,
    topP: 0.9,
    thinkingEffort: "low",
    throttling: 0,
    maxConcurrentRequests: 5,
  };
  const ADAPTIVE = "adaptive" as unknown as AnthropicThinkingEffort;
  const LOW_TO_MAX: AnthropicThinkingEffort[] = [
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
  ];
  const LOW_TO_MAX_WITHOUT_XHIGH: AnthropicThinkingEffort[] = [
    "low",
    "medium",
    "high",
    "max",
  ];

  it.each<[PROVIDER_MODEL_TYPE, AnthropicThinkingEffort[]]>([
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_8, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7_20260416, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_FABLE_5, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_FABLE_5_1, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_5, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_5_1, LOW_TO_MAX],
    [PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_PREVIEW, LOW_TO_MAX_WITHOUT_XHIGH],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, LOW_TO_MAX_WITHOUT_XHIGH],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6_20260205, LOW_TO_MAX_WITHOUT_XHIGH],
    [PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, LOW_TO_MAX_WITHOUT_XHIGH],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_5, ["low", "medium", "high"]],
  ])(
    "offers %s exactly the effort levels Anthropic accepts",
    (model, levels) => {
      expect(supportsAnthropicThinkingEffort(model)).toBe(true);
      expect(
        getAnthropicThinkingEffortOptions(model).map((o) => o.value),
      ).toEqual(levels);
    },
  );

  it.each([
    PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5,
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_3_7,
  ])("offers no effort control for %s, which takes none", (model) => {
    expect(supportsAnthropicThinkingEffort(model)).toBe(false);
    expect(getAnthropicThinkingEffortOptions(model)).toEqual([]);
  });

  it("never lists adaptive, which is a thinking mode rather than an effort level", () => {
    for (const capabilities of Object.values(ANTHROPIC_MODEL_CAPABILITIES)) {
      expect(capabilities?.thinkingEffortOptions ?? []).not.toContain(
        "adaptive",
      );
    }
  });

  it("labels high without claiming it is the provider default", () => {
    const high = getAnthropicThinkingEffortOptions(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
    ).find((o) => o.value === "high");
    expect(high?.label).toBe("High");
  });

  it.each<[PROVIDER_MODEL_TYPE, AnthropicThinkingEffort]>([
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, "high"],
    [PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, "high"],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, "medium"],
  ])("defaults %s to Anthropic's own default, %s", (model, effort) => {
    expect(getDefaultThinkingEffort(model)).toBe(effort);
    expect(resolveEffort(model, {})).toEqual({ thinkingEffort: effort });
  });

  it("coerces an adaptive effort to the model default when switching to Opus 4.7", () => {
    const result = updateProviderConfig(
      { maxCompletionTokens: 4000, thinkingEffort: ADAPTIVE },
      { model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7, provider: ANTHROPIC },
    );
    expect(result?.thinkingEffort).toBe("high");
  });

  it("coerces an effort Opus 5.5 does not take to its medium default", () => {
    const result = updateProviderConfig(
      { maxCompletionTokens: 4000, thinkingEffort: ADAPTIVE },
      { model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, provider: ANTHROPIC },
    );
    expect(result?.thinkingEffort).toBe("medium");
  });

  it("keeps a valid thinkingEffort across model switches", () => {
    const result = updateProviderConfig(
      { maxCompletionTokens: 4000, thinkingEffort: "medium" },
      { model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7, provider: ANTHROPIC },
    );
    expect(result?.thinkingEffort).toBe("medium");
  });

  it("coerces xhigh when switching to Sonnet 4.6, which tops out at max", () => {
    const result = updateProviderConfig(
      { maxCompletionTokens: 4000, thinkingEffort: "xhigh" },
      { model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, provider: ANTHROPIC },
    );
    expect(result?.thinkingEffort).toBe("high");
  });

  it("keeps a stored thinkingEffort the model offers", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        thinkingEffort: "xhigh",
      }),
    ).toEqual({ thinkingEffort: "xhigh" });
  });

  it("falls back to the model default for a thinkingEffort the model does not offer", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        thinkingEffort: ADAPTIVE,
      }),
    ).toEqual({ thinkingEffort: "high" });
  });

  it("reads an effort found only under custom_parameters as stored", () => {
    // A saved optimization run reloads the request shape, which carries no flat thinkingEffort.
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        custom_parameters: { output_config: { effort: "low" } },
      }),
    ).toEqual({ thinkingEffort: "low" });
  });

  it("lets the flat effort the panel just set win over a nested one", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        thinkingEffort: "max",
        custom_parameters: { output_config: { effort: "low" } },
      }),
    ).toEqual({ thinkingEffort: "max" });
  });

  it("skips a flat effort the model does not offer for a valid nested one", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        thinkingEffort: ADAPTIVE,
        custom_parameters: { output_config: { effort: "low" } },
      }),
    ).toEqual({ thinkingEffort: "low" });
  });

  it("falls back to the model default when neither stored effort is offered", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        thinkingEffort: ADAPTIVE,
        custom_parameters: { output_config: { effort: "xhigh" } },
      }),
    ).toEqual({ thinkingEffort: "high" });
  });

  describe("on a Claude model this build has no row for", () => {
    const UNLISTED = "claude-opus-9" as PROVIDER_MODEL_TYPE;

    afterEach(() => {
      resetModelRegistryStoreForTesting();
    });

    it.each<[string, Record<string, unknown>, unknown]>([
      [
        "sends a stored level as is, for Anthropic to judge",
        { output_config: { effort: "xhigh" } },
        { output_config: { effort: "xhigh" } },
      ],
      [
        "drops a value that is not a level name, which the backend rejects",
        { output_config: { effort: "adaptive", format: "x" }, other: 1 },
        { output_config: { format: "x" }, other: 1 },
      ],
      [
        "sends no custom_parameters once an invalid effort was all they held",
        { output_config: { effort: "adaptive" } },
        undefined,
      ],
    ])("%s", (_, stored, expected) => {
      setLatestProviderModelsSnapshot({
        ...getLatestProviderModelsSnapshot(),
        [PROVIDER_TYPE.ANTHROPIC]: [{ value: UNLISTED, label: UNLISTED }],
      });

      expect(
        sanitizeConfigForRequest(UNLISTED, {
          maxCompletionTokens: 4000,
          custom_parameters: stored,
        }).custom_parameters,
      ).toEqual(expected);
    });
  });

  it("sends the valid nested effort rather than replacing it over a stale flat one", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        maxCompletionTokens: 4000,
        thinkingEffort: ADAPTIVE,
        custom_parameters: { output_config: { effort: "low" } },
      }).custom_parameters,
    ).toEqual({ output_config: { effort: "low" } });
  });

  it("sends Sonnet 4.6 temperature alone and its effort under custom_parameters", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        ...FULL_CONFIG,
      }),
    ).toEqual({
      temperature: 0.7,
      maxCompletionTokens: 4000,
      throttling: 0,
      maxConcurrentRequests: 5,
      custom_parameters: { output_config: { effort: "low" } },
    });
  });

  it("sends Sonnet 5 no sampling params and its xhigh effort", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        ...FULL_CONFIG,
        thinkingEffort: "xhigh",
      }),
    ).toEqual({
      maxCompletionTokens: 4000,
      throttling: 0,
      maxConcurrentRequests: 5,
      custom_parameters: { output_config: { effort: "xhigh" } },
    });
  });

  it("replaces an adaptive effort persisted on Opus 4.6 with the default", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, {
        maxCompletionTokens: 4000,
        thinkingEffort: "adaptive",
      }),
    ).toEqual({
      maxCompletionTokens: 4000,
      temperature: 0,
      custom_parameters: { output_config: { effort: "high" } },
    });
  });

  it("keeps the other custom_parameters, inside output_config too", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        maxCompletionTokens: 4000,
        thinkingEffort: "low",
        custom_parameters: {
          thinking: { type: "adaptive" },
          output_config: { format: "x", effort: "max" },
        },
      }).custom_parameters,
    ).toEqual({
      thinking: { type: "adaptive" },
      output_config: { format: "x", effort: "low" },
    });
  });

  it.each([
    ["a string", "json"],
    ["an array", ["x"]],
    ["a number", 3],
  ])(
    "leaves %s output_config untouched for the backend to reject",
    (_, outputConfig) => {
      expect(
        sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
          maxCompletionTokens: 4000,
          thinkingEffort: "low",
          custom_parameters: {
            thinking: { type: "adaptive" },
            output_config: outputConfig,
          },
        }).custom_parameters,
      ).toEqual({
        thinking: { type: "adaptive" },
        output_config: outputConfig,
      });
    },
  );

  it("treats a null output_config as absent", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        maxCompletionTokens: 4000,
        thinkingEffort: "low",
        custom_parameters: { output_config: null },
      }).custom_parameters,
    ).toEqual({ output_config: { effort: "low" } });
  });

  it("removes an effort from a model that takes none", () => {
    // A reloaded optimization run can carry the nested copy onto a model switched to Haiku.
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, {
        maxCompletionTokens: 4000,
        thinkingEffort: "high",
        custom_parameters: { output_config: { effort: "high" } },
      }),
    ).toEqual({ maxCompletionTokens: 4000, temperature: 0 });
  });

  it("never sends a flat thinking_effort", () => {
    for (const model of Object.keys(
      ANTHROPIC_MODEL_CAPABILITIES,
    ) as PROVIDER_MODEL_TYPE[]) {
      expect(
        sanitizeConfigForRequest(model, { ...FULL_CONFIG }),
      ).not.toHaveProperty("thinkingEffort");
    }
  });

  it("sends the effort the dropdown displays", () => {
    for (const model of Object.keys(
      ANTHROPIC_MODEL_CAPABILITIES,
    ) as PROVIDER_MODEL_TYPE[]) {
      const sent = sanitizeConfigForRequest(model, {
        maxCompletionTokens: 4000,
      }).custom_parameters as
        | { output_config?: { effort?: string } }
        | undefined;

      expect(sent?.output_config?.effort).toBe(
        resolveEffort(model, {}).thinkingEffort,
      );
    }
  });
});

describe("Gemini and Vertex AI request contract", () => {
  const FULL_CONFIG: LLMGeminiConfigsType = {
    temperature: 0.4,
    maxCompletionTokens: 2048,
    topP: 0.9,
    thinkingLevel: "high",
    throttling: 0,
    maxConcurrentRequests: 5,
  };
  const BASE_REQUEST = {
    maxCompletionTokens: 2048,
    throttling: 0,
    maxConcurrentRequests: 5,
  };
  const SAMPLING: SamplingParams = { temperature: 0.4, topP: 0.9 };
  const THINKING = { custom_parameters: { thinking: { level: "high" } } };

  describe.each<{
    model: PROVIDER_MODEL_TYPE;
    sampling: SamplingParams;
    request: Record<string, unknown>;
  }>([
    {
      model: PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH,
      sampling: {},
      request: { ...BASE_REQUEST, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO,
      sampling: {},
      request: { ...BASE_REQUEST, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      sampling: {},
      request: { ...BASE_REQUEST, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH,
      sampling: {},
      request: { ...BASE_REQUEST, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH,
      sampling: {},
      request: { ...BASE_REQUEST, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.GEMINI_FLASH_LATEST_HIGH_RES_EXP,
      sampling: {},
      request: BASE_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_ROBOTICS_ER_2,
      sampling: {},
      request: BASE_REQUEST,
    },
    {
      model: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      sampling: SAMPLING,
      request: { ...BASE_REQUEST, ...SAMPLING, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
      sampling: SAMPLING,
      request: { ...BASE_REQUEST, ...SAMPLING, ...THINKING },
    },
    {
      model: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
      sampling: SAMPLING,
      request: { ...BASE_REQUEST, ...SAMPLING },
    },
  ])("$model", ({ model, sampling, request }) => {
    it("resolves the sampling params the panel shows", () => {
      expect(resolveSamplingParams(model, FULL_CONFIG)).toEqual(sampling);
    });

    it("sends exactly the parameters it accepts", () => {
      expect(sanitizeConfigForRequest(model, { ...FULL_CONFIG })).toEqual(
        request,
      );
    });
  });

  it.each([
    PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
    PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_FLASH_PREVIEW,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
    PROVIDER_MODEL_TYPE.GEMINI_FLASH_LATEST_HIGH_RES_EXP,
    PROVIDER_MODEL_TYPE.GEMINI_OMNI_1_1_FLASH,
    PROVIDER_MODEL_TYPE.GEMINI_OMNI_FLASH_PREVIEW,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_OMNI_1_1_FLASH,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_ROBOTICS_ER_2,
  ])("takes no sampling params on %s", (model) => {
    expect(supportsGeminiSamplingParams(model)).toBe(false);
  });

  it.each(["gemini-4-flash", "vertex_ai/gemini-4-pro", "gemini-30-flash"])(
    "takes no sampling params on %s, a generation nobody has checked yet",
    (model) => {
      expect(supportsGeminiSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(
        false,
      );
    },
  );

  it.each([
    PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
    PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH_LITE,
    PROVIDER_MODEL_TYPE.GEMINI_1_5_PRO_LATEST,
    PROVIDER_MODEL_TYPE.GEMINI_1_0_PRO,
    PROVIDER_MODEL_TYPE.GEMINI_PRO_VISION,
    "gemini-2.5",
    "vertex_ai/gemini-1.5",
  ])("keeps sampling params on %s", (model) => {
    expect(supportsGeminiSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(
      true,
    );
  });

  it.each([
    PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_6_FLASH,
    PROVIDER_MODEL_TYPE.GEMMA_4_31B_IT,
    PROVIDER_MODEL_TYPE.GPT_4O,
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5,
    "custom-llm/gw/gemini-4-flash",
  ])("leaves %s alone, since it is not a native Gemini id", (model) => {
    expect(supportsGeminiSamplingParams(model as PROVIDER_MODEL_TYPE)).toBe(
      true,
    );
  });
});

describe("OpenRouter request contract", () => {
  const CONFIG: LLMOpenRouterConfigsType = {
    maxTokens: 0,
    temperature: 0.7,
    topP: 0.9,
    topK: 40,
    frequencyPenalty: 0,
    presencePenalty: 0,
    repetitionPenalty: 1,
    minP: 0,
    topA: 0,
  };
  const WITHOUT_MAX_TOKENS = {
    temperature: 0.7,
    topP: 0.9,
    frequencyPenalty: 0,
    presencePenalty: 0,
    custom_parameters: {
      top_k: 40,
      min_p: 0,
      top_a: 0,
      repetition_penalty: 1,
    },
  };

  describe.each<{
    model: PROVIDER_MODEL_TYPE;
    maxTokens: number;
    request: Record<string, unknown>;
  }>([
    {
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      maxTokens: 0,
      request: WITHOUT_MAX_TOKENS,
    },
    {
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      maxTokens: 512,
      request: { ...WITHOUT_MAX_TOKENS, maxTokens: 512 },
    },
    {
      model: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_6_FLASH,
      maxTokens: 0,
      request: omit(WITHOUT_MAX_TOKENS, ["temperature", "topP"]),
    },
    {
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
      maxTokens: 0,
      request: omit(WITHOUT_MAX_TOKENS, [
        "temperature",
        "topP",
        "frequencyPenalty",
        "presencePenalty",
      ]),
    },
  ])("$model with maxTokens $maxTokens", ({ model, maxTokens, request }) => {
    it("sends exactly the parameters it accepts", () => {
      expect(sanitizeConfigForRequest(model, { ...CONFIG, maxTokens })).toEqual(
        request,
      );
    });
  });

  it("rounds a fractional Top K stored by the old 0.01 slider step", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, {
        ...CONFIG,
        topK: 39.6,
      }).custom_parameters,
    ).toMatchObject({ top_k: 40 });
  });

  it("merges into custom_parameters, letting the panel's values win", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, {
        ...CONFIG,
        custom_parameters: { top_k: 5, transforms: ["middle-out"] },
      }).custom_parameters,
    ).toEqual({
      ...WITHOUT_MAX_TOKENS.custom_parameters,
      transforms: ["middle-out"],
    });
  });

  it.each([
    ["an array", ["middle-out"]],
    ["a string", "middle-out"],
  ])(
    "replaces a custom_parameters that is %s instead of spreading it into numeric keys",
    (_, malformed) => {
      expect(
        sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, {
          topK: 40,
          custom_parameters: malformed,
        }).custom_parameters,
      ).toEqual({ top_k: 40 });
    },
  );

  it("nests only the parameters a stored prompt carries", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, {
        temperature: 0.7,
        topK: 40,
      }),
    ).toEqual({ temperature: 0.7, custom_parameters: { top_k: 40 } });
  });

  it("adds no custom_parameters when the config carries none of them", () => {
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, {
        temperature: 0.7,
      }),
    ).toEqual({ temperature: 0.7 });
  });
});
