import { describe, expect, it } from "vitest";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { LLM_JUDGE } from "@/types/llm";
import { resolveEffort } from "@/lib/modelUtils";

const persisted = (
  model: PROVIDER_MODEL_TYPE,
  custom_parameters: Record<string, unknown> | null,
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

describe("LLM judge Anthropic effort round trip", () => {
  it("reads the persisted effort back out of custom_parameters", () => {
    const data = convertLLMJudgeObjectToLLMJudgeData(
      persisted(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
        output_config: { effort: "low" },
      }),
    );

    expect(data.config.thinkingEffort).toBe("low");
  });

  it("drops a stored adaptive effort, so the rule shows and runs at the model default", () => {
    const data = convertLLMJudgeObjectToLLMJudgeData(
      persisted(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, {
        output_config: { effort: "adaptive" },
      }),
    );

    expect(
      resolveEffort(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, data.config),
    ).toEqual({ thinkingEffort: "medium" });
    expect(
      convertLLMJudgeDataToLLMJudgeObject(
        asFormData(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, data.config),
      ).model.custom_parameters,
    ).toBeUndefined();
  });

  it("saves the selected effort under custom_parameters.output_config", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, {
        thinkingEffort: "xhigh",
      }),
    );

    expect(object.model.custom_parameters).toEqual({
      output_config: { effort: "xhigh" },
    });
    expect(object.model).not.toHaveProperty("thinkingEffort");
  });

  it("keeps the other custom_parameters across an unchanged round trip", () => {
    const stored = {
      thinking: { type: "adaptive" },
      output_config: { format: "x", effort: "max" },
    };

    const object = convertLLMJudgeDataToLLMJudgeObject(
      convertLLMJudgeObjectToLLMJudgeData(
        persisted(PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, stored),
      ),
    );

    expect(object.model.custom_parameters).toEqual(stored);
  });

  it("saves nothing when no effort was chosen, so the model runs at its own default", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, { temperature: 0 }),
    );

    expect(object.model.custom_parameters).toBeUndefined();
  });

  it("drops an effort the model does not offer", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, {
        thinkingEffort: "low",
        custom_parameters: { output_config: { effort: "low" } },
      }),
    );

    expect(object.model.custom_parameters).toBeUndefined();
  });

  it("leaves a custom provider's own output_config alone", () => {
    const customParameters = { output_config: { effort: "low" } };

    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData("custom-llm/gw/claude-sonnet-4-6" as PROVIDER_MODEL_TYPE, {
        custom_parameters: customParameters,
      }),
    );

    expect(object.model.custom_parameters).toEqual(customParameters);
  });
});
