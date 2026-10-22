import { beforeEach, describe, expect, it } from "vitest";

// What GET /v1/private/llm/models and /profiles serve for the bundled registry: regenerate it from the backend, never by hand.
import FIXTURE from "@/lib/anthropicCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderModelsSnapshot,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import {
  getAnthropicThinkingEffortOptions,
  sanitizeConfigForRequest,
  updateProviderConfig,
} from "@/lib/modelUtils";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { ModelParamsByProvider } from "@/types/modelParams";

// The registry's Anthropic answer as GET /v1/private/llm/models served it from the bundled catalog.
// Each case runs twice, against today's tables and against the catalog, and both runs must agree.
const REGISTRY = FIXTURE.models as unknown as LlmModelsByProvider;
const MODELS = REGISTRY[PROVIDER_TYPE.ANTHROPIC].map(
  (m) => m.id as PROVIDER_MODEL_TYPE,
);

const seedTablesOnly = () => {
  resetModelRegistryStoreForTesting();
  setLatestProviderModelsSnapshot({
    ...getLatestProviderModelsSnapshot(),
    [PROVIDER_TYPE.ANTHROPIC]: REGISTRY[PROVIDER_TYPE.ANTHROPIC].map((m) => ({
      value: m.id as PROVIDER_MODEL_TYPE,
      label: m.label ?? m.id,
    })),
  });
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
    temperature: 0.7,
    maxCompletionTokens: 4000,
    topP: 0.9,
    thinkingEffort: "low",
    throttling: 0,
    maxConcurrentRequests: 5,
  },
  { topP: 0.9, maxCompletionTokens: 1000.5 },
  { maxCompletionTokens: 0 },
  {},
  { thinkingEffort: "xhigh", maxCompletionTokens: 64000 },
  {
    thinkingEffort: "max",
    custom_parameters: { output_config: { effort: "low", other: 1 }, keep: 1 },
  },
  {
    thinkingEffort: "adaptive",
    custom_parameters: { output_config: { effort: "medium" } },
  },
  { reasoningEffort: "high", thinkingLevel: "low", frequencyPenalty: 0.2 },
  { temperature: 1.5 },
];

const CASES = MODELS.flatMap((model) =>
  CONFIGS.map((config, index) => [model, index, config] as const),
);

describe("the Anthropic catalog against today's tables", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each(CASES)(
    "sends the same body for %s, config #%s",
    (model, _, config) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        sanitizeConfigForRequest(model, config),
      );
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it.each(CASES)(
    "writes back the same config on a switch to %s, config #%s",
    (model, _, config) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        updateProviderConfig(config, {
          model,
          provider: PROVIDER_TYPE.ANTHROPIC,
        }),
      );
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it.each(MODELS)(
    "offers the same effort levels and defaults for %s",
    (model) => {
      const [fromTables, fromCatalog] = bothWays(() => ({
        options: getAnthropicThinkingEffortOptions(model),
        defaults: getDefaultConfigByProvider(PROVIDER_TYPE.ANTHROPIC, model),
      }));
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it("caps max output tokens at the catalog's limit, not the pricing data's", () => {
    const model = PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6;
    const limit = REGISTRY[PROVIDER_TYPE.ANTHROPIC].find((m) => m.id === model)
      ?.params?.max_output_tokens?.max;
    seedTablesOnly();
    seedCatalog();

    expect(
      sanitizeConfigForRequest(model, { maxCompletionTokens: 10_000_000 })
        .maxCompletionTokens,
    ).toBe(limit);
  });
});
