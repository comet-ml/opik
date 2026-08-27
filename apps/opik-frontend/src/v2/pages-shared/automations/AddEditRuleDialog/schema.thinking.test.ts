import { describe, expect, it } from "vitest";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { LLM_JUDGE } from "@/types/llm";

const persisted = (
  model: PROVIDER_MODEL_TYPE,
  custom_parameters: Record<string, unknown>,
): LLMJudgeObject =>
  ({
    model: { name: model, custom_parameters },
    messages: [],
    variables: {},
    schema: [],
  }) as unknown as LLMJudgeObject;

const asFormData = (model: PROVIDER_MODEL_TYPE, config: unknown) =>
  ({
    model,
    config,
    template: LLM_JUDGE.custom,
    messages: [],
    variables: {},
    schema: [],
    maxCostUsd: null,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any;

describe("LLM judge thinking level round trip", () => {
  it("reads the persisted level back out of custom_parameters", () => {
    const data = convertLLMJudgeObjectToLLMJudgeData(
      persisted(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, {
        thinking: { level: "off" },
      }),
    );

    expect(data.config.thinkingLevel).toBe("off");
  });

  it("keeps a level the model accepts on save", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, {
        thinkingLevel: "off",
        custom_parameters: { thinking: { level: "off" } },
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { level: "off" },
    });
  });

  it("keeps budget_tokens and include_thoughts across an unchanged round trip", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, {
        thinkingLevel: "off",
        custom_parameters: {
          thinking: {
            level: "off",
            budget_tokens: 4096,
            include_thoughts: true,
          },
        },
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { level: "off", budget_tokens: 4096, include_thoughts: true },
    });
  });

  it("drops a persisted level the newly selected model does not accept", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_3_PRO, {
        thinkingLevel: "off",
        custom_parameters: { thinking: { level: "off" } },
      }),
    );

    expect(object.model.custom_parameters).toBeUndefined();
  });

  it("keeps unrelated custom parameters while dropping the rejected level", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_3_PRO, {
        thinkingLevel: "off",
        custom_parameters: { thinking: { level: "off" }, some_other: 1 },
      }),
    );

    expect(object.model.custom_parameters).toEqual({ some_other: 1 });
  });
});
