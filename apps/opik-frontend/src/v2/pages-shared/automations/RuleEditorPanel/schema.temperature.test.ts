import { describe, expect, it } from "vitest";

import { convertLLMJudgeDataToLLMJudgeObject } from "./schema";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { LLM_JUDGE } from "@/types/llm";

const asFormData = (
  model: PROVIDER_MODEL_TYPE,
  temperature?: number,
  customParameters: Record<string, unknown> | null = null,
) =>
  ({
    model,
    config: { temperature, seed: null, custom_parameters: customParameters },
    template: LLM_JUDGE.custom,
    messages: [],
    variables: {},
    schema: [],
    maxCostUsd: null,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any;

// This save path never reaches sanitizeConfigForRequest, so it is the only thing standing between
// the form's temperature and a rule the provider rejects at scoring time.
describe("LLM judge temperature on save", () => {
  it("keeps the temperature for a model that takes one", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GPT_4O, 0.3),
    );

    expect(object.model.temperature).toBe(0.3);
  });

  it("drops it for an Anthropic model that takes no sampling params", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, 0.3),
    );

    expect(object.model.temperature).toBeUndefined();
  });

  it("drops it for an OpenAI reasoning model, which only accepts its own default", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GPT_5_5, 0.3),
    );

    expect(object.model.temperature).toBeUndefined();
  });

  it("does not invent a temperature for a rule that has none", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, undefined),
    );

    expect(object.model.temperature).toBeUndefined();
  });

  it("keeps the temperature for a Gemini thinking model, which does take one", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO, 0.3),
    );

    expect(object.model.temperature).toBe(0.3);
  });

  // OpenAI: "When reasoning effort is not none, remove temperature, top_p, and top_logprobs".
  it("keeps it for an OpenAI reasoning model whose saved effort is none, and only then", () => {
    const atNone = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GPT_6_LUNA, 0, {
        reasoning_effort: "none",
      }),
    );
    const atLow = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GPT_5_4, 0, { reasoning_effort: "low" }),
    );

    expect(atNone.model.temperature).toBe(0);
    expect(atLow.model.temperature).toBeUndefined();
  });

  it("keeps a Claude temperature in the rule while a saved effort makes it think", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, 0.3, {
        output_config: { effort: "high" },
      }),
    );

    expect(object.model.temperature).toBe(0.3);
  });
});
