import { afterEach, beforeEach, describe, expect, it } from "vitest";

import ANTHROPIC_FIXTURE from "@/lib/anthropicCatalog.fixture.json";
import OPENAI_FIXTURE from "@/lib/openaiCatalog.fixture.json";
import GEMINI_FIXTURE from "@/lib/geminiCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import {
  getLatestProviderModelsSnapshot,
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderModelsSnapshot,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import { getModelParams, toParamsRoute } from "@/lib/modelParams";
import {
  getGeminiOrVertexListedParams,
  getThinkingLevelOptions,
  sanitizeConfigForRequest,
} from "@/lib/modelUtils";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { ModelParamsByProvider } from "@/types/modelParams";
import {
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import {
  ANTHROPIC_PANEL,
  GEMINI_PANEL,
  GenericPanelPresentation,
  getShownControls,
  getShownValue,
  OPENAI_PANEL,
  VERTEX_AI_PANEL,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/genericPanels";

// A request-only comparison misses a panel that shows one value and sends another, as it did when
// the panel showed High for a Claude model that thinks only when asked while the request sent no
// effort. This runs every catalog model through the panel and the request on its untouched config.
const REGISTRY = {
  ...ANTHROPIC_FIXTURE.models,
  ...OPENAI_FIXTURE.models,
  ...GEMINI_FIXTURE.models,
} as unknown as LlmModelsByProvider;

const GEMINI_PROVIDERS = [PROVIDER_TYPE.GEMINI, PROVIDER_TYPE.VERTEX_AI];

const nested = (
  request: Record<string, unknown>,
  block: string,
  key: string,
): unknown =>
  (
    (request.custom_parameters as Record<string, unknown> | undefined)?.[
      block
    ] as Record<string, unknown> | undefined
  )?.[key];

// A Gemini request without a level leaves thinking to the model: that is Auto where the model offers
// it, and Off on Gemini 3 and later, which cannot turn thinking off.
const sentEffort = (
  provider: PROVIDER_TYPE,
  model: PROVIDER_MODEL_TYPE,
  request: Record<string, unknown>,
): unknown => {
  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    return nested(request, "output_config", "effort") ?? "off";
  }
  if (GEMINI_PROVIDERS.includes(provider)) {
    return (
      nested(request, "thinking", "level") ??
      (getThinkingLevelOptions(model).some((o) => o.value === "auto")
        ? "auto"
        : "off")
    );
  }
  return request.reasoningEffort;
};

type Case = [
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  GenericPanelPresentation,
  OpenAiPipelineMode | undefined,
  Record<string, unknown>,
];

const UNLISTED_OPENAI = "gpt-unlisted-1" as PROVIDER_MODEL_TYPE;
const UNLISTED_CLAUDE = "claude-unlisted-1" as PROVIDER_MODEL_TYPE;
const MODES = ["chat_completions_api", "responses_api"] as const;

const CASES: Case[] = [
  ...REGISTRY[PROVIDER_TYPE.ANTHROPIC].map(
    (m): Case => [
      m.id as PROVIDER_MODEL_TYPE,
      PROVIDER_TYPE.ANTHROPIC,
      ANTHROPIC_PANEL,
      undefined,
      {},
    ],
  ),
  ...REGISTRY[PROVIDER_TYPE.OPEN_AI].flatMap((m) =>
    MODES.map(
      (mode): Case => [
        m.id as PROVIDER_MODEL_TYPE,
        PROVIDER_TYPE.OPEN_AI,
        OPENAI_PANEL,
        mode,
        {},
      ],
    ),
  ),
  // An id the registry doesn't list gets the provider profile, and a stored value outside its range
  // is shown clamped: the request must send the clamped value too.
  ...MODES.flatMap((mode) =>
    [200000, 5].map(
      (tokens): Case => [
        UNLISTED_OPENAI,
        PROVIDER_TYPE.OPEN_AI,
        OPENAI_PANEL,
        mode,
        { maxCompletionTokens: tokens },
      ],
    ),
  ),
  [
    UNLISTED_CLAUDE,
    PROVIDER_TYPE.ANTHROPIC,
    ANTHROPIC_PANEL,
    undefined,
    { maxCompletionTokens: 200000 },
  ],
  // A stored 0 is shown as 0 and sends no limit; a stored value above the model's limit is shown and
  // sent capped; a level the model does not offer is shown and sent as its default.
  ...GEMINI_PROVIDERS.flatMap((provider) =>
    REGISTRY[provider].flatMap((m) =>
      [
        {},
        { maxCompletionTokens: 0 },
        { maxCompletionTokens: 200000 },
        {
          thinkingLevel: "off",
        },
        { thinkingLevel: "minimal" },
      ].map(
        (stored): Case => [
          (m.qualified_name ?? m.id) as PROVIDER_MODEL_TYPE,
          provider,
          provider === PROVIDER_TYPE.GEMINI ? GEMINI_PANEL : VERTEX_AI_PANEL,
          undefined,
          stored,
        ],
      ),
    ),
  ),
  // A bare id saved under Vertex AI that AI Studio also lists: the panel and the request must both read
  // the same row, whichever provider the prompt names.
  [
    PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
    PROVIDER_TYPE.VERTEX_AI,
    VERTEX_AI_PANEL,
    undefined,
    { maxCompletionTokens: 200000, thinkingLevel: "off" },
  ],
];

// The Gemini and Vertex AI panels open for listed models only: an unlisted id keeps the old panel.
const getPanelParams = (
  provider: PROVIDER_TYPE,
  model: PROVIDER_MODEL_TYPE,
  mode?: OpenAiPipelineMode,
) =>
  GEMINI_PROVIDERS.includes(provider)
    ? getGeminiOrVertexListedParams(model)
    : getModelParams(provider, model, toParamsRoute(mode));

describe("the catalog panel shows what the request sends", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
    setLatestProviderModelsSnapshot({
      ...getLatestProviderModelsSnapshot(),
      ...Object.fromEntries(
        GEMINI_PROVIDERS.map((provider) => [
          provider,
          REGISTRY[provider].map((m) => ({
            value: (m.qualified_name ?? m.id) as PROVIDER_MODEL_TYPE,
            label: m.label ?? m.id,
          })),
        ]),
      ),
    });
    setLatestModelParams(buildParamsIndex(REGISTRY));
    setLatestProviderProfiles({
      ...ANTHROPIC_FIXTURE.profiles,
      ...OPENAI_FIXTURE.profiles,
      ...GEMINI_FIXTURE.profiles,
    } as unknown as ModelParamsByProvider);
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each(CASES)(
    "%s (%s, %s, %s, %o)",
    (model, provider, presentation, mode, stored) => {
      const params = getPanelParams(provider, model, mode)!;
      const configs = {
        ...(getDefaultConfigByProvider(provider, model) as Record<
          string,
          unknown
        >),
        ...stored,
      };
      const request = sanitizeConfigForRequest(model, configs, mode, provider);
      const controls = getShownControls(
        params,
        presentation,
        configs,
        undefined,
        model,
      );

      expect(controls.length).toBeGreaterThan(0);
      for (const control of controls) {
        const shown = getShownValue(
          control,
          params,
          presentation,
          configs,
          model,
        );
        const sent =
          control.kind === "effort"
            ? sentEffort(provider, model, request)
            : control.kind === "sampling_choice"
              ? { temperature: request.temperature, topP: request.topP }
              : request[control.configKey] ??
                (presentation.zeroMeansNoLimit &&
                control.param === "max_output_tokens"
                  ? 0
                  : undefined);
        expect(
          sent,
          control.kind === "number" ? control.param : control.kind,
        ).toEqual(shown);
      }
    },
  );
});
