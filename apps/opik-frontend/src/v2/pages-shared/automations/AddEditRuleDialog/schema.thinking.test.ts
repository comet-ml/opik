import { describe, expect, it } from "vitest";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import {
  COMPOSED_PROVIDER_TYPE,
  GeminiThinkingLevel,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { updateProviderConfig } from "@/lib/modelUtils";
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

  it("keeps include_thoughts across an unchanged round trip", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, {
        thinkingLevel: "low",
        custom_parameters: {
          thinking: {
            level: "low",
            budget_tokens: 4096,
            include_thoughts: true,
          },
        },
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { level: "low", budget_tokens: 4096, include_thoughts: true },
    });
  });

  // A Gemini model whose default level is "auto" contributes no thinking block of its own, so the
  // persisted one must be carried through rather than deleted — budget_tokens and include_thoughts
  // are not represented in the form.
  it("keeps a persisted thinking block when the level resolves to auto", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {
        thinkingLevel: "auto",
        custom_parameters: {
          thinking: { budget_tokens: 4096, include_thoughts: true },
          unrelated: "keep",
        },
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { budget_tokens: 4096, include_thoughts: true },
      unrelated: "keep",
    });
  });

  // "off" is the exception: a persisted budget would outrank it server-side and leave thinking on.
  it("clears a persisted budget when the level is off", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, {
        thinkingLevel: "off",
        custom_parameters: {
          thinking: { budget_tokens: 4096, include_thoughts: true },
        },
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { include_thoughts: true, level: "off" },
    });
  });

  // custom_parameters.thinking is not Gemini-only: Anthropic reads it for extended thinking, so an
  // unedited save of an Anthropic rule must leave the block alone.
  it("leaves thinking untouched for a model without a level control", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData("claude-sonnet-4-5-20250929" as PROVIDER_MODEL_TYPE, {
        custom_parameters: {
          thinking: { type: "enabled", budget_tokens: 4096 },
        },
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { type: "enabled", budget_tokens: 4096 },
    });
  });

  it("persists the level the control shows after a model is picked", () => {
    // What the rule form does on model change: reconcile, then save.
    const initial: {
      temperature: number;
      seed: null;
      custom_parameters: null;
      thinkingLevel?: GeminiThinkingLevel;
    } = { temperature: 0, seed: null, custom_parameters: null };

    const reconciled = updateProviderConfig(initial, {
      model: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      provider: PROVIDER_TYPE.GEMINI as COMPOSED_PROVIDER_TYPE,
    });

    expect(reconciled?.thinkingLevel).toBe("off");

    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, reconciled),
    );

    expect(object.model.custom_parameters).toEqual({
      thinking: { level: "off" },
    });
  });

  // Auto is stored as the absence of a thinking block, and reads back as auto because the reconciler
  // fills in the model default — so the round trip is stable without persisting an Opik-only value.
  it("stores auto as no thinking block and reads it back as auto", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {
        thinkingLevel: "auto",
      }),
    );

    expect(object.model.custom_parameters).toBeUndefined();

    const reloaded = convertLLMJudgeObjectToLLMJudgeData(
      persisted(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {}),
    );

    expect(reloaded.config.thinkingLevel).toBe("auto");
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
