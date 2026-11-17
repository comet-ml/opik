import { beforeEach, describe, expect, it } from "vitest";

// What GET /v1/private/llm/models and /profiles serve for the bundled registry: regenerate it from the backend, never by hand.
import FIXTURE from "@/lib/geminiCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderModelsSnapshot,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import {
  getThinkingLevelOptions,
  sanitizeConfigForRequest,
  supportsGeminiSamplingParams,
  updateProviderConfig,
} from "@/lib/modelUtils";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { ModelParamsByProvider } from "@/types/modelParams";

// Each case runs twice, against today's tables and against the catalog, and both runs must agree
// except where a test below names the difference.
const REGISTRY = FIXTURE.models as unknown as LlmModelsByProvider;
const PROVIDERS = [PROVIDER_TYPE.GEMINI, PROVIDER_TYPE.VERTEX_AI] as const;

const toValue = (m: { id: string; qualified_name?: string }) =>
  (m.qualified_name ?? m.id) as PROVIDER_MODEL_TYPE;

const LISTED = PROVIDERS.flatMap((provider) =>
  REGISTRY[provider].map((m) => [provider, toValue(m)] as const),
);
const UNLISTED = [
  [PROVIDER_TYPE.GEMINI, "gemini-9-unlisted" as PROVIDER_MODEL_TYPE],
  [
    PROVIDER_TYPE.VERTEX_AI,
    "vertex_ai/gemini-9-unlisted" as PROVIDER_MODEL_TYPE,
  ],
] as const;

const MODELS = [...LISTED, ...UNLISTED];

const seedTablesOnly = () => {
  resetModelRegistryStoreForTesting();
  const snapshot = { ...getLatestProviderModelsSnapshot() };
  for (const provider of PROVIDERS) {
    snapshot[provider] = [
      ...REGISTRY[provider].map((m) => ({
        value: toValue(m),
        label: m.label ?? m.id,
      })),
      ...UNLISTED.filter(([p]) => p === provider).map(([, value]) => ({
        value,
        label: value,
      })),
    ];
  }
  setLatestProviderModelsSnapshot(snapshot);
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

// Every stored max stays at or below the lowest catalog limit (4096), which the last test covers.
const CONFIGS: Record<string, unknown>[] = [
  {
    temperature: 0.7,
    maxCompletionTokens: 4000,
    topP: 0.9,
    thinkingLevel: "low",
    throttling: 0,
    maxConcurrentRequests: 5,
  },
  { topP: 0.9, maxCompletionTokens: 1000.5 },
  { maxCompletionTokens: 0 },
  {},
  { thinkingLevel: "minimal" },
  { thinkingLevel: "off" },
  {
    thinkingLevel: "auto",
    custom_parameters: {
      thinking: { level: "high", budget_tokens: 100, include_thoughts: true },
    },
  },
  { thinkingLevel: "high" },
  {
    custom_parameters: {
      thinking: { level: "medium", include_thoughts: true },
    },
  },
  { reasoningEffort: "high", thinkingEffort: "low", frequencyPenalty: 0.2 },
  { temperature: 1.5 },
];

const CASES = MODELS.flatMap(([provider, model]) =>
  CONFIGS.map((config, index) => [model, index, provider, config] as const),
);

const limitOf = (model: PROVIDER_MODEL_TYPE): number | undefined =>
  LISTED.some(([, listed]) => listed === model)
    ? PROVIDERS.flatMap((provider) => REGISTRY[provider]).find(
        (m) => toValue(m) === model,
      )?.params?.max_output_tokens?.max
    : undefined;

describe("the Gemini and Vertex AI catalog against today's tables", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each(CASES)(
    "sends the same body for %s, config #%s",
    (model, _, __, config) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        sanitizeConfigForRequest(model, config),
      );
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it.each(CASES)(
    "writes back the same config on a switch to %s, config #%s",
    (model, _, provider, config) => {
      const [fromTables, fromCatalog] = bothWays(() =>
        updateProviderConfig(config, { model, provider }),
      );
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it.each(MODELS)(
    "offers the same thinking levels, sampling and defaults for %s %s",
    (provider, model) => {
      const [fromTables, fromCatalog] = bothWays(() => ({
        levels: getThinkingLevelOptions(model),
        sampling: supportsGeminiSamplingParams(model),
        defaults: getDefaultConfigByProvider(provider, model),
      }));
      expect(fromCatalog).toEqual(fromTables);
    },
  );

  it.each([...LISTED, ...UNLISTED])(
    "caps max output tokens at the catalog's limit for %s %s",
    (_, model) => {
      const [fromTables, fromCatalog] = bothWays(
        () =>
          sanitizeConfigForRequest(model, { maxCompletionTokens: 10_000_000 })
            .maxCompletionTokens,
      );

      expect(fromTables).toBe(10_000_000);
      expect(fromCatalog).toBe(limitOf(model) ?? 10_000_000);
    },
  );
});
