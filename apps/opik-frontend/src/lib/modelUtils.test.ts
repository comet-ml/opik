import { describe, expect, it } from "vitest";
import {
  getDefaultThinkingLevel,
  getRoutableProviderModelValue,
  getOpenAIReasoningEffortOptions,
  getThinkingLevelOptions,
  sanitizeConfigForRequest,
  supportsGeminiThinkingLevel,
  supportsOpenAIReasoningEffort,
  supportsSamplingParams,
  supportsVertexAIThinkingLevel,
  updateProviderConfig,
} from "@/lib/modelUtils";
import {
  COMPOSED_PROVIDER_TYPE,
  GeminiThinkingLevel,
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

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
});

describe("updateProviderConfig — Anthropic", () => {
  it("strips temperature and topP when switching into Opus 4.7", () => {
    const config: LLMAnthropicConfigsType = {
      temperature: 0.5,
      topP: 0.9,
      maxCompletionTokens: 4000,
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      provider: ANTHROPIC,
    });
    expect(result?.temperature).toBeUndefined();
    expect(result?.topP).toBeUndefined();
    expect(result?.maxCompletionTokens).toBe(4000);
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

  it("coerces invalid thinkingEffort to high when switching to Opus 4.7", () => {
    const config: LLMAnthropicConfigsType = {
      maxCompletionTokens: 4000,
      thinkingEffort: "adaptive",
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      provider: ANTHROPIC,
    });
    expect(result?.thinkingEffort).toBe("high");
  });

  it("keeps a valid thinkingEffort across model switches", () => {
    const config: LLMAnthropicConfigsType = {
      maxCompletionTokens: 4000,
      thinkingEffort: "medium",
    };
    const result = updateProviderConfig(config, {
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      provider: ANTHROPIC,
    });
    expect(result?.thinkingEffort).toBe("medium");
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

  it("labels the default value as 'High (Default)'", () => {
    const opts = getOpenAIReasoningEffortOptions(PROVIDER_MODEL_TYPE.GPT_5);
    const high = opts.find((o) => o.value === "high");
    expect(high?.label).toBe("High (Default)");
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
  it("bumps temperature to 1 when switching into a reasoning model with temp < 1", () => {
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
    expect(result?.temperature).toBe(1);
  });

  it("does not change temperature when already 1, but still strips topP on a reasoning model", () => {
    // topP=1 is the slider default and "harmless" in spirit, but OpenAI rejects any topP value
    // on reasoning models (the constraint is the parameter's presence, not its value). The
    // reconciler strips it; that's a real change so reference equality no longer holds.
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
    expect(result?.temperature).toBe(1);
    expect(result?.topP).toBeUndefined();
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

  it("drops topP when switching into a reasoning OpenAI model", () => {
    // OpenAI rejects top_p with 400 on reasoning models. The reconciler must clear stale
    // values when the user switches from gpt-4o (where top_p is valid) to gpt-5.5.
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
    expect(result?.topP).toBeUndefined();
    // Temperature should also be coerced to 1.0 in the same call.
    expect(result?.temperature).toBe(1.0);
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

  it("strips an unsupported reasoningEffort value (xhigh on gpt-5.1)", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5_1, {
      reasoningEffort: "xhigh",
    });
    expect(result.reasoningEffort).toBeUndefined();
  });

  it("keeps a valid reasoningEffort value for the model", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5, {
      reasoningEffort: "minimal",
    });
    expect(result.reasoningEffort).toBe("minimal");
  });

  it("keeps xhigh for gpt-5.5 (the only OpenAI model that accepts it)", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5_5, {
      reasoningEffort: "xhigh",
    });
    expect(result.reasoningEffort).toBe("xhigh");
  });

  it("does not touch reasoningEffort for non-OpenAI providers", () => {
    const result = sanitizeConfigForRequest(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      {
        reasoningEffort: "high",
      },
    );
    expect(result.reasoningEffort).toBe("high");
  });

  it("strips topP for OpenAI reasoning models", () => {
    // gpt-5.5 is a reasoning model; OpenAI returns 400 if top_p is in the request.
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_5_5, {
      temperature: 1,
      topP: 0.9,
    });
    expect(result.topP).toBeUndefined();
    expect(result.temperature).toBe(1);
  });

  it("keeps topP for non-reasoning OpenAI models", () => {
    const result = sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.GPT_4O, {
      topP: 0.9,
    });
    expect(result.topP).toBe(0.9);
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

    // 3.7 Flash has no "minimal".
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
    // 3.1 Flash Lite has only minimal and high, plus "none" because it does not think by default.
    expect(values(PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE)).toEqual([
      "none",
      "minimal",
      "high",
    ]);
    // Gemini 3 Pro: low and high only.
    expect(values(PROVIDER_MODEL_TYPE.GEMINI_3_PRO)).toEqual(["low", "high"]);
  });

  it("preselects each model's own documented default", () => {
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
