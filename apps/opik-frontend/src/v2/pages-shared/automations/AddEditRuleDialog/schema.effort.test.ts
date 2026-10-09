import { afterEach, describe, expect, it } from "vitest";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  updateConfigForModelChange,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
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

describe("LLM judge Anthropic effort on a model switch", () => {
  const ANTHROPIC = PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE;
  const NO_ROW_MODEL = "claude-opus-9" as PROVIDER_MODEL_TYPE;

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  const openThenSwitch = (
    from: { model: PROVIDER_MODEL_TYPE; provider: COMPOSED_PROVIDER_TYPE },
    to: { model: PROVIDER_MODEL_TYPE; provider: COMPOSED_PROVIDER_TYPE },
    stored: Record<string, unknown>,
  ) => {
    const opened = convertLLMJudgeObjectToLLMJudgeData(
      persisted(from.model, stored),
    ).config;
    const switched = updateConfigForModelChange(opened, from, to);
    return {
      opened,
      switched,
      saved: convertLLMJudgeDataToLLMJudgeObject(asFormData(to.model, switched))
        .model.custom_parameters,
    };
  };

  it.each<[string, PROVIDER_MODEL_TYPE, Record<string, unknown>]>([
    [
      "keeps xhigh on a model that offers it",
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      { output_config: { format: "x", effort: "xhigh" } },
    ],
    [
      "falls back to the default on a model without xhigh",
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
      { output_config: { format: "x", effort: "high" } },
    ],
    [
      "drops it on a model known to take no effort",
      PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      { output_config: { format: "x" } },
    ],
    [
      "drops it on a model this build has no row for",
      NO_ROW_MODEL,
      { output_config: { format: "x" } },
    ],
  ])("switching away from Sonnet 5 at xhigh %s", (_, next, expected) => {
    registerAnthropicModels(NO_ROW_MODEL);

    const { saved } = openThenSwitch(
      { model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, provider: ANTHROPIC },
      { model: next, provider: ANTHROPIC },
      { output_config: { effort: "xhigh", format: "x" } },
    );

    expect(saved).toEqual(expected);
  });

  it.each<[string, Record<string, unknown>, Record<string, unknown>]>([
    [
      "replaces Sonnet 4.6's default high with Opus 5.5's own medium",
      { output_config: { effort: "high" } },
      { output_config: { effort: "medium" } },
    ],
    [
      "keeps a level the user picked",
      { output_config: { effort: "low" } },
      { output_config: { effort: "low" } },
    ],
  ])("switching from Sonnet 4.6 to Opus 5.5 %s", (_, stored, expected) => {
    const { saved } = openThenSwitch(
      { model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, provider: ANTHROPIC },
      { model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5, provider: ANTHROPIC },
      stored,
    );

    expect(saved).toEqual(expected);
  });

  it("keeps the stored effort when the same model with no row is picked again", () => {
    registerAnthropicModels(NO_ROW_MODEL);
    const stored = { output_config: { effort: "xhigh", format: "x" } };

    const { opened, switched, saved } = openThenSwitch(
      { model: NO_ROW_MODEL, provider: ANTHROPIC },
      { model: NO_ROW_MODEL, provider: ANTHROPIC },
      stored,
    );

    expect(switched).toBe(opened);
    expect(saved).toEqual(stored);
  });

  it.each<
    [string, COMPOSED_PROVIDER_TYPE | "", Record<string, unknown> | null]
  >([
    [
      "the previous model's provider is unknown",
      "",
      { output_config: { effort: "high" } },
    ],
    ["the rule has no custom_parameters", ANTHROPIC, null],
    ["output_config is not an object", ANTHROPIC, { output_config: "high" }],
  ])(
    "changes nothing on a switch when %s",
    (_, previousProvider, customParameters) => {
      const config = { temperature: 0, custom_parameters: customParameters };

      const switched = updateConfigForModelChange(
        config,
        {
          model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
          provider: previousProvider,
        },
        { model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, provider: ANTHROPIC },
      );

      expect(switched).toBe(config);
    },
  );

  it("leaves the output_config a custom gateway's JSON holds when switching between its models", () => {
    const gateway = "custom-llm:gw" as COMPOSED_PROVIDER_TYPE;
    const stored = { output_config: { effort: "low" } };

    const { saved } = openThenSwitch(
      {
        model: "custom-llm/gw/claude-sonnet-4-6" as PROVIDER_MODEL_TYPE,
        provider: gateway,
      },
      {
        model: "custom-llm/gw/claude-opus-4-7" as PROVIDER_MODEL_TYPE,
        provider: gateway,
      },
      stored,
    );

    expect(saved).toEqual(stored);
  });
});
