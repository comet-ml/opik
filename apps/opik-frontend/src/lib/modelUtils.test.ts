import { describe, expect, it } from "vitest";
import {
  getOpenAIReasoningEffortOptions,
  sanitizeConfigForRequest,
  supportsOpenAIReasoningEffort,
  supportsSamplingParams,
  updateProviderConfig,
} from "@/lib/modelUtils";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const ANTHROPIC = PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE;
const OPEN_AI = PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE;

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

  it("does not change temperature when already 1", () => {
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
    // Reference equality: nothing changed, same object back.
    expect(result).toBe(config);
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
});
