import { afterEach, describe, expect, it } from "vitest";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { LLM_JUDGE } from "@/types/llm";
import { resolveEffort } from "@/lib/modelUtils";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestProviderModelsSnapshot,
} from "@/lib/modelRegistryStore";

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

// The backend's model registry lists these ids under Anthropic, while the static picker list the
// tests otherwise read does not.
const registerAnthropicModels = (...models: string[]) => {
  const snapshot = getLatestProviderModelsSnapshot();
  setLatestProviderModelsSnapshot({
    ...snapshot,
    [PROVIDER_TYPE.ANTHROPIC]: [
      ...(snapshot[PROVIDER_TYPE.ANTHROPIC] ?? []),
      ...models.map((value) => ({
        value: value as PROVIDER_MODEL_TYPE,
        label: value,
      })),
    ],
  });
};

const unchangedSave = (
  model: PROVIDER_MODEL_TYPE,
  custom_parameters: Record<string, unknown>,
) =>
  convertLLMJudgeDataToLLMJudgeObject(
    asFormData(
      model,
      convertLLMJudgeObjectToLLMJudgeData(persisted(model, custom_parameters))
        .config,
    ),
  ).model.custom_parameters;

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

  describe("on models the backend registry lists", () => {
    afterEach(() => {
      resetModelRegistryStoreForTesting();
    });

    it.each<[PROVIDER_MODEL_TYPE, string]>([
      [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7_20260416, "xhigh"],
      [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6_20260205, "max"],
    ])(
      "keeps the effort of the dated %s through an unchanged save",
      (model, effort) => {
        registerAnthropicModels(model);
        const stored = {
          output_config: { effort },
          unrelated_marker: "keep-me",
        };

        expect(unchangedSave(model, stored)).toEqual(stored);
      },
    );

    it("keeps the effort of a Claude model this build has no row for", () => {
      const model = "claude-opus-9" as PROVIDER_MODEL_TYPE;
      registerAnthropicModels(model);
      const stored = { output_config: { effort: "xhigh", format: "x" } };

      expect(
        convertLLMJudgeObjectToLLMJudgeData(persisted(model, stored)).config
          .thinkingEffort,
      ).toBeUndefined();
      expect(unchangedSave(model, stored)).toEqual(stored);
    });

    it("drops a stored value that is not a level name from a Claude model with no row", () => {
      const model = "claude-opus-9" as PROVIDER_MODEL_TYPE;
      registerAnthropicModels(model);

      expect(
        unchangedSave(model, {
          output_config: { effort: "adaptive", format: "x" },
        }),
      ).toEqual({ output_config: { format: "x" } });
    });

    it("still drops an effort from a model known to take none", () => {
      expect(
        unchangedSave(PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, {
          output_config: { effort: "low", format: "x" },
        }),
      ).toEqual({ output_config: { format: "x" } });
    });
  });
});
