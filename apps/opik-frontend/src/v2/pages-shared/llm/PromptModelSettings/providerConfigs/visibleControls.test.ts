import { afterEach, describe, expect, it } from "vitest";

import {
  createSupports,
  getAnthropicVisibleControls,
  getOpenAIVisibleControls,
  getOpenRouterVisibleControls,
  hasVisibleControls,
} from "./visibleControls";
import {
  EffortParams,
  isAnthropicThinkingRequested,
  resolveSamplingParams,
  sanitizeConfigForRequest,
} from "@/lib/modelUtils";
import {
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import {
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import GEMINI_FIXTURE from "@/lib/geminiCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import {
  resetModelRegistryStoreForTesting,
  getLatestProviderModelsSnapshot,
  setLatestModelFlags,
  setLatestModelParams,
  setLatestProviderModelsSnapshot,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";

const ANTHROPIC_CONFIG = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  throttling: 0,
  maxConcurrentRequests: 5,
};

const RULE_CONFIG = { temperature: 0.4 };

describe("hasVisibleControls", () => {
  it("is false on a rule for a newly synced Claude with no capability row", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        "claude-opus-9" as PROVIDER_MODEL_TYPE,
        ANTHROPIC_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("is true for a Claude model without sampling params on a rule, which keeps its effort", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
        ANTHROPIC_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(true);
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

  it.each([
    [PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O_MINI],
    [PROVIDER_TYPE.GEMINI, PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH],
    [PROVIDER_TYPE.VERTEX_AI, PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH],
  ])(
    "is false for %s on a rule whose config carries only max output tokens",
    (provider, model) => {
      expect(
        hasVisibleControls(
          provider,
          model,
          { maxCompletionTokens: 4000 },
          RULE_UNSUPPORTED_PARAMS,
        ),
      ).toBe(false);
    },
  );

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

  it("is false for an openrouter OpenAI reasoning model on a rule", () => {
    expect(
      hasVisibleControls(
        PROVIDER_TYPE.OPEN_ROUTER,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_6_ASTRA,
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

  it.each([PROVIDER_TYPE.OLLAMA, PROVIDER_TYPE.BEDROCK])(
    "is true for %s, which shares the custom panel and its JSON editor",
    (provider) => {
      expect(hasVisibleControls(provider, "", {})).toBe(true);
      expect(
        hasVisibleControls(provider, "", RULE_CONFIG, RULE_UNSUPPORTED_PARAMS),
      ).toBe(true);
      expect(
        hasVisibleControls(provider, "", {}, OPTIMIZATION_UNSUPPORTED_PARAMS),
      ).toBe(true);
    },
  );

  it("is false for the Opik free model, which has no panel", () => {
    expect(hasVisibleControls(PROVIDER_TYPE.OPIK_FREE, "", {})).toBe(false);
  });
});

describe("the OpenRouter sampling and penalty sliders", () => {
  const OPEN_ROUTER_CONFIG: LLMOpenRouterConfigsType = {
    maxTokens: 0,
    temperature: 1,
    topP: 1,
    topK: 0,
    frequencyPenalty: 0,
    presencePenalty: 0,
    repetitionPenalty: 1,
    minP: 0,
    topA: 0,
  };

  it.each<[PROVIDER_MODEL_TYPE, boolean, boolean]>([
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO, false, false],
    [PROVIDER_MODEL_TYPE.OPENAI_O3, false, false],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW, false, true],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI, true, true],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH, true, true],
    [PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH, true, true],
  ])("on %s show sampling: %s, penalties: %s", (model, sampling, penalties) => {
    const visible = getOpenRouterVisibleControls({
      model,
      configs: OPEN_ROUTER_CONFIG,
      supports: createSupports(),
    });

    expect({
      temperature: visible.temperature,
      topP: visible.topP,
      frequencyPenalty: visible.frequencyPenalty,
      presencePenalty: visible.presencePenalty,
    }).toEqual({
      temperature: sampling,
      topP: sampling,
      frequencyPenalty: penalties,
      presencePenalty: penalties,
    });
  });
});

describe("the OpenRouter panel on a model with OpenRouter's lists", () => {
  const OPEN_ROUTER_CONFIG: LLMOpenRouterConfigsType = {
    maxTokens: 0,
    temperature: 1,
    topP: 1,
    topK: 0,
    frequencyPenalty: 0,
    presencePenalty: 0,
    repetitionPenalty: 1,
    minP: 0,
    topA: 0,
  };

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each<[string, string[], string[], string[]]>([
    [
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      [
        "frequency_penalty",
        "max_tokens",
        "presence_penalty",
        "temperature",
        "top_p",
      ],
      [],
      [
        "temperature",
        "maxTokens",
        "topP",
        "frequencyPenalty",
        "presencePenalty",
      ],
    ],
    [
      PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
      ["max_tokens", "reasoning", "reasoning_effort"],
      ["high", "medium", "low", "minimal"],
      ["maxTokens", "reasoningEffort"],
    ],
    [
      PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_HAIKU_4_5,
      ["max_tokens", "reasoning", "temperature", "top_k", "top_p"],
      [],
      ["samplingParams", "maxTokens", "topK"],
    ],
    [
      PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_8B_INSTRUCT,
      [
        "frequency_penalty",
        "max_tokens",
        "min_p",
        "presence_penalty",
        "repetition_penalty",
        "temperature",
        "top_k",
        "top_p",
      ],
      [],
      [
        "temperature",
        "maxTokens",
        "topP",
        "topK",
        "frequencyPenalty",
        "presencePenalty",
        "repetitionPenalty",
        "minP",
      ],
    ],
  ])("on %s shows only what it lists", (model, params, efforts, shown) => {
    setLatestModelFlags(
      new Map([
        [
          model,
          {
            reasoning: false,
            structuredOutput: false,
            supportedParameters: params,
            reasoningEfforts: efforts,
          },
        ],
      ]),
    );

    const visible = getOpenRouterVisibleControls({
      model: model as PROVIDER_MODEL_TYPE,
      configs: OPEN_ROUTER_CONFIG,
      supports: createSupports(
        new Set(["throttling", "maxConcurrentRequests"]),
      ),
    });

    expect(
      Object.entries(visible)
        .filter(([, isVisible]) => isVisible)
        .map(([control]) => control),
    ).toEqual(shown);
  });

  it("hides the effort on a surface that cannot store it", () => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
          {
            reasoning: false,
            structuredOutput: false,
            supportedParameters: ["reasoning_effort"],
            reasoningEfforts: ["low"],
          },
        ],
      ]),
    );

    expect(
      getOpenRouterVisibleControls({
        model: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
        configs: { temperature: 0 },
        supports: createSupports(RULE_UNSUPPORTED_PARAMS),
      }).reasoningEffort,
    ).toBe(false);
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

describe("Anthropic sampling params while Claude thinks", () => {
  const SONNET_4_6 = PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6;
  const NOT_A_LEVEL = "adaptive" as EffortParams["thinkingEffort"];

  const expectAllAgree = (
    model: PROVIDER_MODEL_TYPE,
    configs: EffortParams,
    thinking: boolean,
  ) => {
    const config = { ...ANTHROPIC_CONFIG, ...configs };

    expect(isAnthropicThinkingRequested(model, config)).toBe(thinking);
    expect(resolveSamplingParams(model, config)).toEqual(
      thinking ? {} : { temperature: 0.4 },
    );
    expect(
      getAnthropicVisibleControls({
        model,
        configs: config,
        supports: createSupports(),
      }).samplingParams,
    ).toBe(!thinking);
    expect(sanitizeConfigForRequest(model, config).temperature).toBe(
      thinking ? undefined : 0.4,
    );
  };

  it.each<[string, boolean, EffortParams]>([
    ["no effort anywhere", false, {}],
    [
      "a flat effort that is not a level",
      false,
      { thinkingEffort: NOT_A_LEVEL },
    ],
    ["a flat xhigh, above what it offers", false, { thinkingEffort: "xhigh" }],
    [
      "a null output_config",
      false,
      { custom_parameters: { output_config: null } },
    ],
    [
      "a scalar output_config",
      false,
      { custom_parameters: { output_config: "low" } },
    ],
    [
      "an output_config effort that is not a level",
      false,
      { custom_parameters: { output_config: { effort: "adaptive" } } },
    ],
    [
      "a flat level beside a null output_config",
      true,
      { thinkingEffort: "low", custom_parameters: { output_config: null } },
    ],
    [
      "a flat level beside a scalar output_config",
      true,
      { thinkingEffort: "low", custom_parameters: { output_config: "max" } },
    ],
    [
      "an invalid flat effort beside a nested level",
      true,
      {
        thinkingEffort: NOT_A_LEVEL,
        custom_parameters: { output_config: { effort: "low" } },
      },
    ],
  ])("agree on Sonnet 4.6 with %s, thinking: %s", (_, thinking, configs) => {
    expectAllAgree(SONNET_4_6, configs, thinking);
  });

  it.each<[string, boolean, PROVIDER_MODEL_TYPE, EffortParams]>([
    [
      "a disabled block beside a level",
      false,
      SONNET_4_6,
      {
        thinkingEffort: "high",
        custom_parameters: { thinking: { type: "disabled" } },
      },
    ],
    [
      "an upper-case DISABLED block beside a level",
      false,
      SONNET_4_6,
      {
        thinkingEffort: "high",
        custom_parameters: { thinking: { type: "DISABLED" } },
      },
    ],
    [
      "a block without a type beside a level",
      false,
      SONNET_4_6,
      { thinkingEffort: "high", custom_parameters: { thinking: {} } },
    ],
    [
      "a block with a blank type beside a level",
      false,
      SONNET_4_6,
      {
        thinkingEffort: "high",
        custom_parameters: { thinking: { type: " " } },
      },
    ],
    [
      "an adaptive block at Off",
      true,
      SONNET_4_6,
      {
        thinkingEffort: "off",
        custom_parameters: { thinking: { type: "adaptive" } },
      },
    ],
    [
      "a null block beside a level, which counts as no block",
      true,
      SONNET_4_6,
      { thinkingEffort: "high", custom_parameters: { thinking: null } },
    ],
    [
      "an enabled block on Haiku 4.5, which has no effort",
      true,
      PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      {
        custom_parameters: {
          thinking: { type: "enabled", budget_tokens: 2000 },
        },
      },
    ],
  ])(
    "let a thinking block in custom_parameters decide: %s, thinking: %s",
    (_, thinking, model, configs) => {
      expectAllAgree(model, configs, thinking);
    },
  );
});

describe("hasVisibleControls on the catalog panel", () => {
  const MODEL = "claude-fixture-1" as PROVIDER_MODEL_TYPE;
  const REFUSES_ALL = {
    temperature: { accepted: false },
    top_p: { accepted: false },
    max_output_tokens: { accepted: true, min: 1, max: 8000, default: 4000 },
    effort: { accepted: false },
    sampling_exclusive: true,
  };

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("hides the settings button on a rule when the model's params leave nothing to set", () => {
    setLatestModelParams(new Map([[PROVIDER_TYPE.ANTHROPIC, new Map()]]));
    setLatestProviderProfiles({ [PROVIDER_TYPE.ANTHROPIC]: REFUSES_ALL });

    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        MODEL,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });

  it("shows it once the model takes an effort", () => {
    setLatestProviderModelsSnapshot({
      ...getLatestProviderModelsSnapshot(),
      [PROVIDER_TYPE.ANTHROPIC]: [{ value: MODEL, label: MODEL }],
    });
    setLatestModelParams(
      new Map([
        [
          PROVIDER_TYPE.ANTHROPIC,
          new Map([
            [
              MODEL,
              {
                ...REFUSES_ALL,
                effort: {
                  accepted: true,
                  levels: ["low", "high"],
                  default: "high",
                },
              },
            ],
          ]),
        ],
      ]),
    );

    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        MODEL,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(true);
  });

  it("offers no effort for a Claude id the registry doesn't list, as the request sends none for it", () => {
    setLatestModelParams(new Map([[PROVIDER_TYPE.ANTHROPIC, new Map()]]));
    setLatestProviderProfiles({
      [PROVIDER_TYPE.ANTHROPIC]: {
        ...REFUSES_ALL,
        effort: { accepted: true, levels: ["low", "high"], default: "high" },
      },
    });

    expect(
      hasVisibleControls(
        PROVIDER_TYPE.ANTHROPIC,
        MODEL,
        RULE_CONFIG,
        RULE_UNSUPPORTED_PARAMS,
      ),
    ).toBe(false);
  });
});

describe("hasVisibleControls on the Gemini and Vertex AI catalog panel", () => {
  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  const seedCatalog = () =>
    setLatestModelParams(
      buildParamsIndex(GEMINI_FIXTURE.models as unknown as LlmModelsByProvider),
    );

  it.each([
    [PROVIDER_TYPE.GEMINI, "gemini-3.5-transcribe"],
    [PROVIDER_TYPE.VERTEX_AI, "vertex_ai/gemini-robotics-er-2"],
  ])(
    "hides the settings button for %s %s on a rule, which takes neither sampling nor a level",
    (provider, model) => {
      seedCatalog();

      expect(
        hasVisibleControls(
          provider,
          model as PROVIDER_MODEL_TYPE,
          RULE_CONFIG,
          RULE_UNSUPPORTED_PARAMS,
        ),
      ).toBe(false);
    },
  );

  it.each([
    [PROVIDER_TYPE.GEMINI, PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH],
    [PROVIDER_TYPE.VERTEX_AI, PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH],
  ])(
    "shows it for %s %s on a rule, for the thinking level",
    (provider, model) => {
      seedCatalog();

      expect(
        hasVisibleControls(
          provider,
          model,
          RULE_CONFIG,
          RULE_UNSUPPORTED_PARAMS,
        ),
      ).toBe(true);
    },
  );
});
