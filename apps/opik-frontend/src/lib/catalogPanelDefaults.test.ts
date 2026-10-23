import { afterEach, beforeEach, describe, expect, it } from "vitest";

import ANTHROPIC_FIXTURE from "@/lib/anthropicCatalog.fixture.json";
import OPENAI_FIXTURE from "@/lib/openaiCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import { getModelParams, toParamsRoute } from "@/lib/modelParams";
import { sanitizeConfigForRequest } from "@/lib/modelUtils";
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
  GenericPanelPresentation,
  getShownControls,
  getShownValue,
  OPENAI_PANEL,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/genericPanels";

// A request-only comparison misses a panel that shows one value and sends another, as it did when
// the panel showed High for a Claude model that thinks only when asked while the request sent no
// effort. This runs every catalog model through the panel and the request on its untouched config.
const REGISTRY = {
  ...ANTHROPIC_FIXTURE.models,
  ...OPENAI_FIXTURE.models,
} as unknown as LlmModelsByProvider;

const sentEffort = (
  provider: PROVIDER_TYPE,
  request: Record<string, unknown>,
): unknown =>
  provider === PROVIDER_TYPE.ANTHROPIC
    ? (
        (request.custom_parameters as Record<string, unknown> | undefined)
          ?.output_config as Record<string, unknown> | undefined
      )?.effort ?? "off"
    : request.reasoningEffort;

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
];

describe("the catalog panel shows what the request sends", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
    setLatestModelParams(buildParamsIndex(REGISTRY));
    setLatestProviderProfiles({
      ...ANTHROPIC_FIXTURE.profiles,
      ...OPENAI_FIXTURE.profiles,
    } as unknown as ModelParamsByProvider);
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each(CASES)(
    "%s (%s, %s, %s, %o)",
    (model, provider, presentation, mode, stored) => {
      const params = getModelParams(provider, model, toParamsRoute(mode))!;
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
            ? sentEffort(provider, request)
            : control.kind === "sampling_choice"
              ? { temperature: request.temperature, topP: request.topP }
              : request[control.configKey];
        expect(
          sent,
          control.kind === "number" ? control.param : control.kind,
        ).toEqual(shown);
      }
    },
  );
});
