import { describe, expect, it } from "vitest";

import { hasVisibleControls } from "./visibleControls";
import {
  ModelConfigParam,
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

const ANTHROPIC_CONFIG = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  throttling: 0,
  maxConcurrentRequests: 5,
};

const RULE_CONFIG = { temperature: 0.4 };

const SAMPLING_CONFIG = { temperature: 0.4, maxCompletionTokens: 4000 };

const NO_TEMPERATURE_RULE_PARAMS: ReadonlySet<ModelConfigParam> = new Set([
  ...RULE_UNSUPPORTED_PARAMS,
  "temperature",
]);

describe("hasVisibleControls", () => {
  it("is false for a Claude model without sampling params on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
        ANTHROPIC_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("is true for the same Claude model on the playground", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
        ANTHROPIC_CONFIG,
      ),
    ).toBe(true);
  });

  it("is false for an OpenAI reasoning model on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_AI,
        PROVIDER_MODEL_TYPE.GPT_6_ASTRA,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("is true for an OpenAI model that takes temperature on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_AI,
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(true);
  });

  it("is false for an OpenAI model on a rule whose config carries max output tokens", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_AI,
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        { maxCompletionTokens: 4000 },
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it.each([
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI],
    [PROVIDER_TYPE.GEMINI, PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH],
    [PROVIDER_TYPE.VERTEX_AI, PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH],
  ])(
    "is false for %s when the surface supports neither temperature nor max output tokens",
    (provider, model) => {
      expect(
        hasVisibleControls(
          provider,
          model,
          SAMPLING_CONFIG,
          NO_TEMPERATURE_RULE_PARAMS,
        ),
      ).toBe(false);
    },
  );

  it.each([
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI],
    [PROVIDER_TYPE.GEMINI, PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH],
    [PROVIDER_TYPE.VERTEX_AI, PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH],
  ])(
    "is true for %s when the surface supports max output tokens but not temperature",
    (provider, model) => {
      expect(
        hasVisibleControls(
          provider,
          model,
          SAMPLING_CONFIG,
          new Set<ModelConfigParam>(["temperature"]),
        ),
      ).toBe(true);
    },
  );

  it("is false for openrouter with only a temperature the surface cannot store", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        RULE_CONFIG,
        NO_TEMPERATURE_RULE_PARAMS,
      ),
    ).toBe(false);
  });

  it("is false for a Gemini 3 model without a thinking row on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.GEMINI,
        "gemini-3.9-flash" as PROVIDER_MODEL_TYPE,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("is true for openrouter with a temperature on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        "",
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(true);
  });

  it("is false for openrouter on the optimizer, whose config starts empty", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        {},
        OPTIMIZATION_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("is false for an openrouter Claude model without sampling params on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_5,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("is true for openrouter on the playground with only a temperature", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        RULE_CONFIG,
      ),
    ).toBe(true);
  });

  it("is true for a custom provider, whose JSON editor always renders", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.CUSTOM,
        "",
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(true);
  });

  it.each([
    PROVIDER_TYPE.OPIK_FREE,
    PROVIDER_TYPE.OLLAMA,
    PROVIDER_TYPE.BEDROCK,
  ])("is false for %s, which has no panel", (provider) => {
    expect(hasVisibleControls(provider, "", {})).toBe(false);
  });
});
