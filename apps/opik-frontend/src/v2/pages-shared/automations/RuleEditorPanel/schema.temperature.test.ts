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

  it("drops it for an OpenAI reasoning model with no none level, which never takes one", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GPT_O3, 0.3),
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

  // OpenAI: "When reasoning effort is not none, remove temperature, top_p, and top_logprobs". The
  // judge does that per request, so the rule keeps the value for when the effort goes back to none.
  it.each([
    [PROVIDER_MODEL_TYPE.GPT_6_LUNA, { reasoning_effort: "none" }],
    [PROVIDER_MODEL_TYPE.GPT_6_LUNA, { reasoning_effort: "medium" }],
    [PROVIDER_MODEL_TYPE.GPT_5_4, { reasoning_effort: "low" }],
    [PROVIDER_MODEL_TYPE.GPT_5_5, null],
  ])(
    "keeps it for %s, which takes one at effort none, whatever effort the rule saved",
    (model, customParameters) => {
      const object = convertLLMJudgeDataToLLMJudgeObject(
        asFormData(model, 0, customParameters),
      );

      expect(object.model.temperature).toBe(0);
    },
  );

  it("keeps a Claude temperature in the rule while a saved effort makes it think", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, 0.3, {
        output_config: { effort: "high" },
      }),
    );

    expect(object.model.temperature).toBe(0.3);
  });
});
