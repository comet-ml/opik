import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  updateConfigForModelChange,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import {
  COMPOSED_PROVIDER_TYPE,
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { LLM_JUDGE } from "@/types/llm";
import { resolveEffort } from "@/lib/modelUtils";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
} from "@/lib/modelRegistryStore";

const OPEN_AI = PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE;
const OPEN_ROUTER_MODEL = PROVIDER_MODEL_TYPE.OPENAI_GPT_6_LUNA;

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

const open = (
  model: PROVIDER_MODEL_TYPE,
  custom_parameters: Record<string, unknown> | null,
) =>
  convertLLMJudgeObjectToLLMJudgeData(persisted(model, custom_parameters))
    .config;

const save = (
  model: PROVIDER_MODEL_TYPE,
  config: unknown,
  openAiPipelineMode?: OpenAiPipelineMode,
) =>
  convertLLMJudgeDataToLLMJudgeObject(
    asFormData(model, config),
    openAiPipelineMode,
  ).model.custom_parameters;

const unchangedSave = (
  model: PROVIDER_MODEL_TYPE,
  custom_parameters: Record<string, unknown>,
  openAiPipelineMode?: OpenAiPipelineMode,
) => save(model, open(model, custom_parameters), openAiPipelineMode);

describe("LLM judge OpenAI effort round trip", () => {
  it("reads the persisted effort back out of custom_parameters", () => {
    expect(
      open(PROVIDER_MODEL_TYPE.GPT_6_LUNA, { reasoning_effort: "low" })
        .reasoningEffort,
    ).toBe("low");
  });

  it("saves the selected effort as custom_parameters.reasoning_effort", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      asFormData(PROVIDER_MODEL_TYPE.GPT_6_LUNA, { reasoningEffort: "high" }),
    );

    expect(object.model.custom_parameters).toEqual({
      reasoning_effort: "high",
    });
    expect(object.model).not.toHaveProperty("reasoningEffort");
  });

  it("saves nothing when no effort was chosen, so the model runs at its own default", () => {
    const config = { temperature: 0 };

    expect(save(PROVIDER_MODEL_TYPE.GPT_6_LUNA, config)).toBeUndefined();
    expect(resolveEffort(PROVIDER_MODEL_TYPE.GPT_6_LUNA, {})).toEqual({
      reasoningEffort: "medium",
    });
  });

  it.each<[string, Record<string, unknown>]>([
    ["a level with other keys", { reasoning_effort: "low", user: "judge" }],
    ["the Responses-only max", { reasoning_effort: "max" }],
  ])("keeps %s across an unchanged save", (_, stored) => {
    expect(unchangedSave(PROVIDER_MODEL_TYPE.GPT_6_LUNA, stored)).toEqual(
      stored,
    );
  });

  it("drops a stored value that is not a level name from a model that offers effort", () => {
    expect(
      unchangedSave(PROVIDER_MODEL_TYPE.GPT_6_LUNA, {
        reasoning_effort: "adaptive",
        user: "judge",
      }),
    ).toEqual({ user: "judge" });
  });

  it.each<[string, PROVIDER_MODEL_TYPE]>([
    ["a model the form shows no effort for", PROVIDER_MODEL_TYPE.GPT_4O],
    [
      "a Custom LLM, whose extra body is its own",
      "custom-llm/gw/gpt-6-luna" as PROVIDER_MODEL_TYPE,
    ],
  ])("sends the stored effort of %s as it is", (_, model) => {
    const stored = { reasoning_effort: "low" };

    expect(open(model, stored).reasoningEffort).toBeUndefined();
    expect(unchangedSave(model, stored)).toEqual(stored);
  });
});

describe("LLM judge OpenAI effort and the key's mode", () => {
  it("saves no effort for a max the Chat Completions key shows as the default", () => {
    const config = open(PROVIDER_MODEL_TYPE.GPT_6_LUNA, {
      reasoning_effort: "max",
      user: "judge",
    });

    expect(
      resolveEffort(
        PROVIDER_MODEL_TYPE.GPT_6_LUNA,
        config,
        "chat_completions_api",
      ),
    ).toEqual({ reasoningEffort: "medium" });
    expect(
      save(PROVIDER_MODEL_TYPE.GPT_6_LUNA, config, "chat_completions_api"),
    ).toEqual({ user: "judge" });
  });

  it.each<[string, OpenAiPipelineMode | undefined, Record<string, unknown>]>([
    [
      "keeps max on a Responses key",
      "responses_api",
      { reasoning_effort: "max" },
    ],
    ["keeps max while the keys load", undefined, { reasoning_effort: "max" }],
    [
      "keeps xhigh on a Chat Completions key",
      "chat_completions_api",
      { reasoning_effort: "xhigh" },
    ],
  ])("%s", (_, openAiPipelineMode, stored) => {
    expect(
      unchangedSave(PROVIDER_MODEL_TYPE.GPT_6_LUNA, stored, openAiPipelineMode),
    ).toEqual(stored);
  });
});

describe("LLM judge OpenAI effort in a Responses-shaped reasoning object", () => {
  const pick = (
    stored: Record<string, unknown>,
    reasoningEffort: string | undefined,
  ) =>
    save(PROVIDER_MODEL_TYPE.GPT_6_LUNA, {
      ...open(PROVIDER_MODEL_TYPE.GPT_6_LUNA, stored),
      reasoningEffort,
    });

  it.each<[string, Record<string, unknown>, string]>([
    ["reads reasoning.effort", { reasoning: { effort: "low" } }, "low"],
    [
      "prefers reasoning_effort when both are set",
      { reasoning_effort: "high", reasoning: { effort: "low" } },
      "high",
    ],
  ])("%s", (_, stored, expected) => {
    expect(open(PROVIDER_MODEL_TYPE.GPT_6_LUNA, stored).reasoningEffort).toBe(
      expected,
    );
  });

  it.each<
    [
      string,
      Record<string, unknown>,
      string | undefined,
      Record<string, unknown> | undefined,
    ]
  >([
    [
      "moves a lone nested effort to reasoning_effort",
      { reasoning: { effort: "low" } },
      "high",
      { reasoning_effort: "high" },
    ],
    [
      "keeps the effort inside reasoning next to its other keys",
      { reasoning: { effort: "low", summary: "auto" } },
      "high",
      { reasoning: { summary: "auto", effort: "high" } },
    ],
    [
      "moves reasoning_effort inside a reasoning that would replace it",
      { reasoning_effort: "low", reasoning: { summary: "auto" } },
      "low",
      { reasoning: { summary: "auto", effort: "low" } },
    ],
    [
      "drops both copies on Default",
      {
        reasoning_effort: "low",
        reasoning: { effort: "high", summary: "auto" },
      },
      undefined,
      { reasoning: { summary: "auto" } },
    ],
    [
      "drops a reasoning left empty on Default",
      { reasoning: { effort: "low" } },
      undefined,
      undefined,
    ],
  ])("%s", (_, stored, reasoningEffort, expected) => {
    expect(pick(stored, reasoningEffort)).toEqual(expected);
  });

  it("sets reasoning_effort next to a reasoning that is not an object", () => {
    expect(pick({ reasoning: "high" }, "low")).toEqual({
      reasoning: "high",
      reasoning_effort: "low",
    });
  });
});

describe("LLM judge OpenAI effort on a model switch", () => {
  const openThenSwitch = (
    from: PROVIDER_MODEL_TYPE,
    to: PROVIDER_MODEL_TYPE,
    stored: Record<string, unknown>,
    openAiPipelineMode?: OpenAiPipelineMode,
  ) => {
    const switched = updateConfigForModelChange(
      open(from, stored),
      { model: from, provider: OPEN_AI },
      { model: to, provider: OPEN_AI },
      openAiPipelineMode,
    );
    return save(to, switched);
  };

  it.each<
    [
      string,
      PROVIDER_MODEL_TYPE,
      Record<string, unknown>,
      OpenAiPipelineMode | undefined,
      Record<string, unknown> | undefined,
    ]
  >([
    [
      "keeps a level the next model offers",
      PROVIDER_MODEL_TYPE.GPT_6_SOL,
      { reasoning_effort: "low", user: "judge" },
      "chat_completions_api",
      { reasoning_effort: "low", user: "judge" },
    ],
    [
      "moves max to the nearest level on a Responses key",
      PROVIDER_MODEL_TYPE.GPT_5_5,
      { reasoning_effort: "max" },
      "responses_api",
      { reasoning_effort: "xhigh" },
    ],
    [
      "moves max, shown as the default on a Chat Completions key, to the next default",
      PROVIDER_MODEL_TYPE.GPT_5_5,
      { reasoning_effort: "max" },
      "chat_completions_api",
      { reasoning_effort: "medium" },
    ],
    [
      "drops it on a model that takes no effort",
      PROVIDER_MODEL_TYPE.GPT_4O,
      { reasoning_effort: "low", user: "judge" },
      "chat_completions_api",
      { user: "judge" },
    ],
    [
      "drops custom_parameters left empty",
      PROVIDER_MODEL_TYPE.GPT_4O,
      { reasoning_effort: "low" },
      undefined,
      undefined,
    ],
    [
      "keeps a nested level the next model offers inside reasoning",
      PROVIDER_MODEL_TYPE.GPT_6_SOL,
      { reasoning: { effort: "low", summary: "auto" } },
      "responses_api",
      { reasoning: { summary: "auto", effort: "low" } },
    ],
    [
      "drops a nested effort on a model that takes no effort",
      PROVIDER_MODEL_TYPE.GPT_4O,
      { reasoning: { effort: "low", summary: "auto" } },
      "responses_api",
      { reasoning: { summary: "auto" } },
    ],
  ])(
    "switching from GPT 6 Luna %s",
    (_, next, stored, openAiPipelineMode, expected) => {
      expect(
        openThenSwitch(
          PROVIDER_MODEL_TYPE.GPT_6_LUNA,
          next,
          stored,
          openAiPipelineMode,
        ),
      ).toEqual(expected);
    },
  );

  it("drops the effort on a switch to OpenRouter", () => {
    const switched = updateConfigForModelChange(
      open(PROVIDER_MODEL_TYPE.GPT_6_LUNA, { reasoning_effort: "low" }),
      { model: PROVIDER_MODEL_TYPE.GPT_6_LUNA, provider: OPEN_AI },
      {
        model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
        provider: PROVIDER_TYPE.OPEN_ROUTER as COMPOSED_PROVIDER_TYPE,
      },
    );

    expect(save(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O, switched)).toBeUndefined();
  });
});

describe("LLM judge OpenRouter effort", () => {
  beforeEach(() => {
    setLatestModelFlags(
      new Map([
        [
          OPEN_ROUTER_MODEL,
          {
            reasoning: true,
            structuredOutput: false,
            supportedParameters: ["max_tokens", "reasoning"],
            reasoningEfforts: ["low", "medium", "high"],
          },
        ],
      ]),
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("reads the persisted effort back out of custom_parameters.reasoning", () => {
    expect(
      open(OPEN_ROUTER_MODEL, { reasoning: { effort: "high" } })
        .reasoningEffort,
    ).toBe("high");
  });

  it("saves the selected effort under custom_parameters.reasoning, keeping its other keys", () => {
    expect(
      save(OPEN_ROUTER_MODEL, {
        reasoningEffort: "low",
        custom_parameters: { reasoning: { exclude: true }, user: "judge" },
      }),
    ).toEqual({ reasoning: { exclude: true, effort: "low" }, user: "judge" });
  });

  it.each<
    [string, Record<string, unknown>, Record<string, unknown> | undefined]
  >([
    [
      "keeps the other reasoning keys",
      { reasoning: { effort: "low", exclude: true } },
      { reasoning: { exclude: true } },
    ],
    [
      "drops a reasoning left empty",
      { reasoning: { effort: "low" } },
      undefined,
    ],
  ])("saving Default %s", (_, stored, expected) => {
    expect(
      save(OPEN_ROUTER_MODEL, {
        ...open(OPEN_ROUTER_MODEL, stored),
        reasoningEffort: undefined,
      }),
    ).toEqual(expected);
  });

  it("drops a level the model does not list", () => {
    expect(
      unchangedSave(OPEN_ROUTER_MODEL, { reasoning: { effort: "xhigh" } }),
    ).toBeUndefined();
  });

  it("leaves a reasoning that is not an object alone", () => {
    const stored = { reasoning: "high" };

    expect(unchangedSave(OPEN_ROUTER_MODEL, stored)).toEqual(stored);
  });
});
