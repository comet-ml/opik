import { describe, expect, it, vi } from "vitest";
import {
  getAnthropicThinkingEffortOptions,
  resolveEffort,
  sanitizeConfigForRequest,
  supportsAnthropicThinkingEffort,
  updateProviderConfig,
} from "@/lib/modelUtils";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  AnthropicThinkingEffort,
  COMPOSED_PROVIDER_TYPE,
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

// Pins the behaviour the Anthropic effort control gets back once the backend forwards it, so
// flipping the constant is the whole change.
vi.mock("@/constants/llm", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/constants/llm")>()),
  THINKING_CONTROLS_FORWARDED_BY_BACKEND: true,
}));

const ANTHROPIC = PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE;
// Earlier releases stored it; it is no longer part of the type.
const ADAPTIVE = "adaptive" as unknown as AnthropicThinkingEffort;

describe("Anthropic thinking effort once the backend forwards it", () => {
  it.each<[PROVIDER_MODEL_TYPE, AnthropicThinkingEffort[]]>([
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, ["low", "medium", "high", "max"]],
    [PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, ["low", "medium", "high", "max"]],
    [
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
      ["low", "medium", "high", "xhigh", "max"],
    ],
  ])("offers %s its effort levels", (model, levels) => {
    expect(supportsAnthropicThinkingEffort(model)).toBe(true);
    expect(
      getAnthropicThinkingEffortOptions(model).map((o) => o.value),
    ).toEqual(levels);
  });

  it("labels high without claiming it is the provider default", () => {
    const high = getAnthropicThinkingEffortOptions(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
    ).find((o) => o.value === "high");
    expect(high?.label).toBe("High");
  });

  it("seeds high on a new config", () => {
    expect(
      getDefaultConfigByProvider(
        ANTHROPIC,
        PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      ),
    ).toMatchObject({ thinkingEffort: "high" });
  });

  it("coerces invalid thinkingEffort to high when switching to Opus 4.7", () => {
    const config: LLMAnthropicConfigsType = {
      maxCompletionTokens: 4000,
      thinkingEffort: ADAPTIVE,
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

  it("keeps a stored thinkingEffort the model offers", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        thinkingEffort: "xhigh",
      }),
    ).toEqual({ thinkingEffort: "xhigh" });
  });

  it("falls back to high for a thinkingEffort the model does not offer", () => {
    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        thinkingEffort: ADAPTIVE,
      }),
    ).toEqual({ thinkingEffort: "high" });
  });

  it("replaces an Anthropic thinkingEffort the model does not offer", () => {
    // updateProviderConfig coerces this on a model change, but a stored prompt whose model is still
    // valid is never reconciled, so the wire needs its own answer.
    expect(
      sanitizeConfigForRequest(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        thinkingEffort: ADAPTIVE,
        maxCompletionTokens: 4000,
      }).thinkingEffort,
    ).toBe("high");
  });
});
