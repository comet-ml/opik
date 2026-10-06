import { describe, expect, it } from "vitest";

import {
  createSupports,
  getOpenAIVisibleControls,
  hasVisibleControls,
} from "./visibleControls";
import {
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import {
  LLMOpenAIConfigsType,
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const ANTHROPIC_CONFIG = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  throttling: 0,
  maxConcurrentRequests: 5,
};

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

describe("the OpenAI penalty sliders", () => {
  const OPENAI_CONFIG: LLMOpenAIConfigsType = {
    temperature: 0.4,
    maxCompletionTokens: 4000,
    topP: 1,
    frequencyPenalty: 0.5,
    presencePenalty: 0.3,
  };

  const penalties = (mode?: OpenAiPipelineMode) => {
    const { frequencyPenalty, presencePenalty } = getOpenAIVisibleControls({
      model: PROVIDER_MODEL_TYPE.GPT_4O,
      configs: OPENAI_CONFIG,
      supports: createSupports(),
      openAiPipelineMode: mode,
    });
    return { frequencyPenalty, presencePenalty };
  };

  it("are hidden on a Responses API key", () => {
    expect(penalties("responses_api")).toEqual({
      frequencyPenalty: false,
      presencePenalty: false,
    });
  });

  it.each<OpenAiPipelineMode | undefined>([undefined, "chat_completions_api"])(
    "are shown when the mode is %s",
    (mode) => {
      expect(penalties(mode)).toEqual({
        frequencyPenalty: true,
        presencePenalty: true,
      });
    },
  );

  // Throttling is hidden so that the only controls left are the ones the config carries.
  const anyVisible = (
    configs: Partial<LLMOpenAIConfigsType>,
    mode?: OpenAiPipelineMode,
  ) =>
    hasVisibleControls(
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O,
      configs,
      OPTIMIZATION_UNSUPPORTED_PARAMS,
      mode,
    );

  it("leave the other controls visible on a Responses API key", () => {
    expect(anyVisible(OPENAI_CONFIG, "responses_api")).toBe(true);
  });

  it("leave nothing to show when the config holds only penalties on a Responses API key", () => {
    const onlyPenalties = { frequencyPenalty: 0.5, presencePenalty: 0.3 };

    expect(anyVisible(onlyPenalties, "responses_api")).toBe(false);
    expect(anyVisible(onlyPenalties, "chat_completions_api")).toBe(true);
  });
});
