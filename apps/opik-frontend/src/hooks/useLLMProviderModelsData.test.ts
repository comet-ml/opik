import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useLLMProviderModelsData from "./useLLMProviderModelsData";
import { getProviderFromModel } from "@/lib/provider";
import { DECISION_MODELS } from "@/constants/decisionModels";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { getLatestModelFlags } from "@/lib/modelRegistryStore";

const registry = vi.hoisted(() => ({
  data: undefined as LlmModelsByProvider | undefined,
}));

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({
    data: registry.data,
    isPending: false,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => ({}),
}));

const GEMINI_2_5_PRO = {
  id: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
  label: "Gemini 2.5 Pro",
  structuredOutput: true,
  reasoning: true,
};

const GEMINI_AND_VERTEX_REGISTRY: LlmModelsByProvider = {
  [PROVIDER_TYPE.GEMINI]: [GEMINI_2_5_PRO],
  [PROVIDER_TYPE.VERTEX_AI]: [GEMINI_2_5_PRO],
};

beforeEach(() => {
  registry.data = GEMINI_AND_VERTEX_REGISTRY;
});

describe("useLLMProviderModelsData", () => {
  it("uses provider hints to resolve legacy bare Vertex AI model ids", () => {
    const { result } = renderHook(() => useLLMProviderModelsData());

    expect(
      result.current.calculateModelProvider(PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO),
    ).toBe(PROVIDER_TYPE.GEMINI);

    expect(
      result.current.calculateModelProvider(
        PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
        PROVIDER_TYPE.VERTEX_AI,
      ),
    ).toBe(PROVIDER_TYPE.VERTEX_AI);

    expect(
      result.current.calculateModelProvider(
        PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
        PROVIDER_TYPE.GEMINI,
      ),
    ).toBe(PROVIDER_TYPE.GEMINI);
  });

  it("resolves decisions models to OpenRouter without listing them in the dropdown", () => {
    const { result } = renderHook(() => useLLMProviderModelsData());

    DECISION_MODELS.forEach(({ value }) => {
      expect(result.current.calculateModelProvider(value)).toBe(
        PROVIDER_TYPE.OPEN_ROUTER,
      );
      expect(getProviderFromModel(value)).toBe(PROVIDER_TYPE.OPEN_ROUTER);
      // Only callers that opt in (the rule dialog) list them.
      expect(
        result.current.providerModels[PROVIDER_TYPE.OPEN_ROUTER]?.map(
          (m) => m.value,
        ) ?? [],
      ).not.toContain(value);
    });
  });
});

describe("useLLMProviderModelsData OpenRouter lists", () => {
  it("indexes the parameters and effort levels the registry sends", () => {
    registry.data = {
      [PROVIDER_TYPE.OPEN_ROUTER]: [
        {
          id: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
          label: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
          structuredOutput: true,
          reasoning: false,
          supported_parameters: ["max_tokens", "reasoning_effort"],
          reasoning_efforts: ["high", "low"],
        },
      ],
    };

    renderHook(() => useLLMProviderModelsData());

    expect(
      getLatestModelFlags(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO),
    ).toMatchObject({
      supportedParameters: ["max_tokens", "reasoning_effort"],
      reasoningEfforts: ["high", "low"],
    });
  });
});

describe("useLLMProviderModelsData hasRegistryModels", () => {
  it("is true when a known provider has models", () => {
    const { result } = renderHook(() => useLLMProviderModelsData());

    expect(result.current.hasRegistryModels).toBe(true);
  });

  it.each<[string, LlmModelsByProvider | undefined]>([
    ["the registry has not answered", undefined],
    [
      "every provider has an empty model list",
      { [PROVIDER_TYPE.OPEN_AI]: [], [PROVIDER_TYPE.ANTHROPIC]: [] },
    ],
    ["only an unknown provider has models", { "acme-llm": [GEMINI_2_5_PRO] }],
  ])("is false when %s", (_, data) => {
    registry.data = data;
    const { result } = renderHook(() => useLLMProviderModelsData());

    expect(result.current.hasRegistryModels).toBe(false);
  });
});

const model = (
  id: PROVIDER_MODEL_TYPE,
  extra: { label?: string; qualifiedName?: string } = {},
) => ({ id, structuredOutput: false, reasoning: false, ...extra });

const PICKER_REGISTRY: LlmModelsByProvider = {
  [PROVIDER_TYPE.OPEN_AI]: [
    model(PROVIDER_MODEL_TYPE.GPT_4O_MINI, { label: "GPT 4o Mini" }),
    model(PROVIDER_MODEL_TYPE.GPT_LIVE_1),
  ],
  [PROVIDER_TYPE.GEMINI]: [
    model(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, { label: "Gemini 2.5 Flash" }),
    model(PROVIDER_MODEL_TYPE.GEMINI_OMNI_1_1_FLASH),
    model(PROVIDER_MODEL_TYPE.LYRIA_3_5),
  ],
  [PROVIDER_TYPE.VERTEX_AI]: [
    model(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, {
      qualifiedName: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      label: "Gemini 2.5 Flash",
    }),
    model(PROVIDER_MODEL_TYPE.GEMINI_OMNI_1_1_FLASH, {
      qualifiedName: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_OMNI_1_1_FLASH,
    }),
  ],
};

describe("useLLMProviderModelsData isDropdownModel", () => {
  beforeEach(() => {
    registry.data = PICKER_REGISTRY;
  });

  it.each<[PROVIDER_MODEL_TYPE | "", PROVIDER_TYPE | "", boolean]>([
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, PROVIDER_TYPE.OPEN_AI, true],
    [PROVIDER_MODEL_TYPE.GPT_LIVE_1, PROVIDER_TYPE.OPEN_AI, false],
    [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, PROVIDER_TYPE.GEMINI, true],
    [PROVIDER_MODEL_TYPE.GEMINI_OMNI_1_1_FLASH, PROVIDER_TYPE.GEMINI, false],
    [PROVIDER_MODEL_TYPE.LYRIA_3_5, PROVIDER_TYPE.GEMINI, false],
    [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, PROVIDER_TYPE.VERTEX_AI, true],
    [
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      PROVIDER_TYPE.VERTEX_AI,
      true,
    ],
    [
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_OMNI_1_1_FLASH,
      PROVIDER_TYPE.VERTEX_AI,
      false,
    ],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, PROVIDER_TYPE.GEMINI, false],
    ["", PROVIDER_TYPE.OPEN_AI, false],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, "", false],
  ])("%j under %j is in the picker: %s", (modelName, provider, expected) => {
    const { result } = renderHook(() => useLLMProviderModelsData());

    expect(result.current.isDropdownModel(modelName, provider)).toBe(expected);
  });

  it("keeps a stored model that left the picker valid, so stored prompts keep it", () => {
    const { result } = renderHook(() => useLLMProviderModelsData());

    expect(
      result.current.calculateModelProvider(PROVIDER_MODEL_TYPE.GPT_LIVE_1),
    ).toBe(PROVIDER_TYPE.OPEN_AI);
    expect(
      result.current.calculateDefaultModel(PROVIDER_MODEL_TYPE.GPT_LIVE_1, [
        PROVIDER_TYPE.OPEN_AI,
      ]),
    ).toBe(PROVIDER_MODEL_TYPE.GPT_LIVE_1);
  });
});
