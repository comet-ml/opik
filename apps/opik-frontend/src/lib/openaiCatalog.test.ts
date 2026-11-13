import { beforeEach, describe, expect, it } from "vitest";
import omit from "lodash/omit";

// What GET /v1/private/llm/models and /profiles serve for the bundled registry: regenerate it from the backend, never by hand.
import FIXTURE from "@/lib/openaiCatalog.fixture.json";
import {
  buildFlagsIndex,
  buildParamsIndex,
} from "@/hooks/useLLMProviderModelsData";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
  setLatestModelParams,
  setLatestProviderModelsSnapshot,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import {
  getOpenAIReasoningEffortOptions,
  sanitizeConfigForRequest,
  updateProviderConfig,
} from "@/lib/modelUtils";
import { getModelParams, toParamsRoute } from "@/lib/modelParams";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import {
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { ModelParamsByProvider } from "@/types/modelParams";
import {
  createSupports,
  getOpenAIVisibleControls,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/visibleControls";
import {
  getShownControls,
  OPENAI_PANEL,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/genericPanels";
import {
  ModelConfigParam,
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

// The registry's OpenAI answer as GET /v1/private/llm/models serves it from the bundled catalog, after the first
// sync that wrote each model's params. Each case runs twice, against today's tables and against the catalog, and
// both runs must agree except where the catalog is the more accurate side, which the last block spells out.
const REGISTRY = FIXTURE.models as unknown as LlmModelsByProvider;
const OPENAI = PROVIDER_TYPE.OPEN_AI;
const MODELS = REGISTRY[OPENAI].map((m) => m.id as PROVIDER_MODEL_TYPE);
const MODES: (OpenAiPipelineMode | undefined)[] = [
  undefined,
  "chat_completions_api",
  "responses_api",
];

const seedTablesOnly = () => {
  resetModelRegistryStoreForTesting();
  setLatestProviderModelsSnapshot({
    ...getLatestProviderModelsSnapshot(),
    [OPENAI]: REGISTRY[OPENAI].map((m) => ({
      value: m.id as PROVIDER_MODEL_TYPE,
      label: m.label ?? m.id,
    })),
  });
  setLatestModelFlags(buildFlagsIndex(REGISTRY));
};

const seedCatalog = () => {
  setLatestModelParams(buildParamsIndex(REGISTRY));
  setLatestProviderProfiles(
    FIXTURE.profiles as unknown as ModelParamsByProvider,
  );
};

const bothWays = <T>(run: () => T): [T, T] => {
  seedTablesOnly();
  const fromTables = run();
  seedCatalog();
  return [fromTables, run()];
};

const CONFIGS: Record<string, unknown>[] = [
  {
    temperature: 0,
    maxCompletionTokens: 2000,
    topP: 1,
    frequencyPenalty: 0,
    presencePenalty: 0,
    reasoningEffort: "high",
    throttling: 0,
    maxConcurrentRequests: 5,
  },
  {
    temperature: 0.7,
    topP: 0.9,
    maxCompletionTokens: 1000.5,
    frequencyPenalty: 0.3,
    presencePenalty: -0.2,
    reasoningEffort: "low",
  },
  {},
  { reasoningEffort: "medium", maxCompletionTokens: 10 },
  {
    thinkingEffort: "low",
    thinkingLevel: "low",
    custom_parameters: { a: 1 },
    reasoningEffort: "low",
  },
  { temperature: 0, seed: null, custom_parameters: null },
];

// A switch moves these to the nearest level the next model offers; the tables moved them to high.
const LEVELS_SOME_MODELS_LACK: Record<string, unknown>[] = [
  { reasoningEffort: "minimal" },
  { reasoningEffort: "none", temperature: 1.5 },
  { reasoningEffort: "xhigh" },
  { reasoningEffort: "max" },
];

const SURFACES: [string, ReadonlySet<ModelConfigParam> | undefined][] = [
  ["the playground", undefined],
  ["an evaluator rule", RULE_UNSUPPORTED_PARAMS],
  ["the optimizer", OPTIMIZATION_UNSUPPORTED_PARAMS],
];

const shownByTables = (
  model: PROVIDER_MODEL_TYPE,
  configs: Record<string, unknown>,
  mode: OpenAiPipelineMode | undefined,
  unsupported?: ReadonlySet<ModelConfigParam>,
): string[] =>
  Object.entries(
    getOpenAIVisibleControls({
      model,
      configs,
      supports: createSupports(unsupported),
      openAiPipelineMode: mode,
    }),
  )
    .filter(([, shown]) => shown)
    .map(([key]) => key)
    .sort();

const shownByCatalog = (
  model: PROVIDER_MODEL_TYPE,
  configs: Record<string, unknown>,
  mode: OpenAiPipelineMode | undefined,
  unsupported?: ReadonlySet<ModelConfigParam>,
): string[] => {
  const params = getModelParams(OPENAI, model, toParamsRoute(mode));
  const supports = createSupports(unsupported);
  return [
    ...(params
      ? getShownControls(params, OPENAI_PANEL, configs, unsupported).map(
          (control) =>
            control.kind === "sampling_choice" ? "sampling" : control.configKey,
        )
      : []),
    ...(supports("throttling") ? ["throttling"] : []),
    ...(supports("maxConcurrentRequests") ? ["maxConcurrentRequests"] : []),
  ].sort();
};

// Levels from the curated rows, which follow OpenAI's model pages; the tables offer these models no effort.
const DOCUMENTED_EFFORT_LEVELS: Record<string, string[]> = {
  "gpt-5-pro": ["high"],
  "gpt-5.2-pro": ["medium", "high", "xhigh"],
  "gpt-5.4-pro": ["medium", "high", "xhigh"],
  "gpt-5.5-pro": ["medium", "high", "xhigh"],
  "gpt-5.2-codex": ["low", "medium", "high", "xhigh"],
  "gpt-5.3-codex": ["low", "medium", "high", "xhigh"],
  "o1-2024-12-17": ["low", "medium", "high"],
};

const TRANSCRIBE_MODEL = "gpt-4o-transcribe-diarize" as PROVIDER_MODEL_TYPE;

const tokenFloor = (model: string): number =>
  REGISTRY[OPENAI].find((m) => m.id === model)?.params?.max_output_tokens
    ?.min ?? 1;

const withoutDocumentedEffort = <T>(model: string, value: T): T =>
  model in DOCUMENTED_EFFORT_LEVELS
    ? (omit(value as object, "reasoningEffort") as T)
    : value;

const atTokenFloor = (
  model: string,
  body: Record<string, unknown>,
): Record<string, unknown> => {
  const tokens = body.maxCompletionTokens;
  return typeof tokens === "number" && tokens < tokenFloor(model)
    ? { ...body, maxCompletionTokens: tokenFloor(model) }
    : body;
};

const CASES = MODELS.flatMap((model) =>
  MODES.map((mode) => [model, mode ?? "an unknown mode"] as const),
);

const modeOf = (mode: string): OpenAiPipelineMode | undefined =>
  mode === "an unknown mode" ? undefined : (mode as OpenAiPipelineMode);

describe("the OpenAI catalog against today's tables", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each(CASES)("sends the same bodies for %s on %s", (model, mode) => {
    for (const config of [...CONFIGS, ...LEVELS_SOME_MODELS_LACK]) {
      const [fromTables, fromCatalog] = bothWays(() =>
        sanitizeConfigForRequest(model, config, modeOf(mode)),
      );
      expect(
        withoutDocumentedEffort(model, fromCatalog),
        JSON.stringify(config),
      ).toEqual(atTokenFloor(model, fromTables));
    }
  });

  it.each(CASES)(
    "writes back the same configs on a switch to %s on %s",
    (model, mode) => {
      for (const config of CONFIGS) {
        const [fromTables, fromCatalog] = bothWays(() =>
          updateProviderConfig(config, {
            model,
            provider: OPENAI,
            openAiPipelineMode: modeOf(mode),
          }),
        );
        expect(
          withoutDocumentedEffort(model, fromCatalog),
          JSON.stringify(config),
        ).toEqual(withoutDocumentedEffort(model, fromTables));
      }
    },
  );

  it.each(CASES.filter(([model]) => !(model in DOCUMENTED_EFFORT_LEVELS)))(
    "offers the same effort levels for %s on %s",
    (model, mode) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        getOpenAIReasoningEffortOptions(model, modeOf(mode)),
      );
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it.each(CASES)("shows the same controls for %s on %s", (model, mode) => {
    for (const [surface, unsupported] of SURFACES) {
      for (const config of CONFIGS) {
        seedTablesOnly();
        const fromTables = shownByTables(
          model,
          config,
          modeOf(mode),
          unsupported,
        );
        seedCatalog();
        expect(
          shownByCatalog(model, config, modeOf(mode), unsupported).filter(
            (control) =>
              !(model in DOCUMENTED_EFFORT_LEVELS) ||
              control !== "reasoningEffort",
          ),
          `${surface}, ${JSON.stringify(config)}`,
        ).toEqual(fromTables);
      }
    }
  });

  it.each(MODELS.filter((model) => model !== TRANSCRIBE_MODEL))(
    "seeds the same default config for %s",
    (model) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        getDefaultConfigByProvider(OPENAI, model),
      );
      expect(withoutDocumentedEffort(model, fromCatalog)).toEqual(fromTables);
    },
  );
});

describe("where the catalog differs from today's tables", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each(Object.entries(DOCUMENTED_EFFORT_LEVELS))(
    "offers the effort levels OpenAI documents for %s",
    (model, levels) => {
      for (const mode of MODES) {
        const [fromTables, fromCatalog] = bothWays(() =>
          getOpenAIReasoningEffortOptions(model as PROVIDER_MODEL_TYPE, mode),
        );
        expect(fromTables).toEqual([]);
        expect(fromCatalog.map((option) => option.value)).toEqual(levels);
      }
    },
  );

  it.each(["gpt-5.5", "o3", "gpt-5.1-codex"])(
    "raises a token limit below 16 to 16 for %s, where OpenAI answers less with a 400",
    (model) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        sanitizeConfigForRequest(
          model as PROVIDER_MODEL_TYPE,
          { reasoningEffort: "medium", maxCompletionTokens: 10 },
          "chat_completions_api",
        ),
      );
      expect(fromTables.maxCompletionTokens).toBe(10);
      expect(fromCatalog.maxCompletionTokens).toBe(16);
    },
  );

  it("starts gpt-4o-transcribe-diarize at its own 2,000-token limit", () => {
    const [fromTables, fromCatalog] = bothWays(
      () =>
        getDefaultConfigByProvider(OPENAI, TRANSCRIBE_MODEL) as {
          maxCompletionTokens?: number;
        },
    );
    expect(fromTables.maxCompletionTokens).toBe(4000);
    expect(fromCatalog.maxCompletionTokens).toBe(2000);
  });
});

describe("a switch away from a max only the Responses API offers", () => {
  beforeEach(() => {
    seedTablesOnly();
    seedCatalog();
  });

  it.each([
    ["gpt-5.5", "an unknown mode", "medium"],
    ["gpt-5.5", "responses_api", "xhigh"],
    ["gpt-6-luna", "an unknown mode", "max"],
  ])("switching gpt-6-sol to %s on %s leaves %s", (model, mode, expected) => {
    expect(
      updateProviderConfig(
        { reasoningEffort: "max" },
        {
          model: model as PROVIDER_MODEL_TYPE,
          provider: OPENAI,
          openAiPipelineMode: modeOf(mode),
          previousModel: "gpt-6-sol" as PROVIDER_MODEL_TYPE,
        },
      )?.reasoningEffort,
    ).toBe(expected);
  });
});
