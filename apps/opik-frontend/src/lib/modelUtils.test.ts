import { describe, expect, it } from "vitest";
import {
  sanitizeConfigForRequest,
  supportsSamplingParams,
  updateProviderConfig,
} from "@/lib/modelUtils";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const ANTHROPIC = PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE;

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
});
