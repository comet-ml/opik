import { describe, expect, it } from "vitest";

import { hasVisibleControls } from "./visibleControls";
import {
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { getDefaultConfigByProvider } from "@/lib/playground";

const ANTHROPIC_CONFIG = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  throttling: 0,
  maxConcurrentRequests: 5,
};

// A rule's config holds only temperature and seed.
const RULE_CONFIG = { temperature: 0.4 };

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

  it("is true for openrouter on the playground with its default config", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        getDefaultConfigByProvider(
          PROVIDER_TYPE.OPEN_ROUTER,
          PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        ),
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
