import { beforeEach, describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";

import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
} from "@/lib/modelRegistryStore";
import { sortProviderModels, useModelOptions } from "./useModelOptions";

const createProvider = (
  provider: PROVIDER_TYPE,
  configuration: Record<string, string> = {},
): ProviderObject =>
  ({
    id: provider,
    created_at: "",
    provider,
    ui_composed_provider: provider,
    configuration,
    read_only: false,
  }) as ProviderObject;

describe("sortProviderModels", () => {
  it("keeps non-OpenRouter providers unchanged", () => {
    const models = [
      { label: "z-model", value: "z-model" },
      { label: "a-model", value: "a-model" },
    ];

    expect(sortProviderModels(PROVIDER_TYPE.OPEN_AI, models)).toEqual(models);
  });

  it("sorts OpenRouter models with openrouter/* routes first and A-Z", () => {
    const models = [
      { label: "zeta", value: "zeta/model" },
      { label: "openrouter/free", value: "openrouter/free" },
      { label: "alpha", value: "alpha/model" },
      { label: "openrouter/auto", value: "openrouter/auto" },
    ];

    expect(sortProviderModels(PROVIDER_TYPE.OPEN_ROUTER, models)).toEqual([
      { label: "openrouter/auto", value: "openrouter/auto" },
      { label: "openrouter/free", value: "openrouter/free" },
      { label: "alpha", value: "alpha/model" },
      { label: "zeta", value: "zeta/model" },
    ]);
  });
});

describe("useModelOptions", () => {
  it("qualifies Vertex AI model values before exposing selector options", () => {
    const providers = [
      createProvider(PROVIDER_TYPE.GEMINI),
      createProvider(PROVIDER_TYPE.VERTEX_AI),
    ];

    const providerModelsMap = {
      [PROVIDER_TYPE.GEMINI]: [
        {
          label: "Gemini 2.5 Pro",
          value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
        },
      ],
      [PROVIDER_TYPE.VERTEX_AI]: [
        {
          label: "Gemini 2.5 Pro",
          value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
        },
      ],
    };

    const { result } = renderHook(() =>
      useModelOptions(providers, providerModelsMap, ""),
    );

    expect(result.current.groupOptions).toEqual([
      expect.objectContaining({
        composedProviderType: PROVIDER_TYPE.GEMINI,
        options: [
          {
            label: "Gemini 2.5 Pro",
            value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
          },
        ],
      }),
      expect.objectContaining({
        composedProviderType: PROVIDER_TYPE.VERTEX_AI,
        options: [
          {
            label: "Gemini 2.5 Pro",
            value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
          },
        ],
      }),
    ]);

    expect(
      result.current.modelProviderMapRef.current[
        PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO
      ],
    ).toBe(PROVIDER_TYPE.GEMINI);
    expect(
      result.current.modelProviderMapRef.current[
        PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO
      ],
    ).toBe(PROVIDER_TYPE.VERTEX_AI);
  });

  it("keeps already-qualified Vertex AI model values unchanged", () => {
    const providers = [createProvider(PROVIDER_TYPE.VERTEX_AI)];
    const providerModelsMap = {
      [PROVIDER_TYPE.VERTEX_AI]: [
        {
          label: "Gemini 2.5 Pro",
          value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
        },
      ],
    };

    const { result } = renderHook(() =>
      useModelOptions(providers, providerModelsMap, ""),
    );

    expect(result.current.groupOptions[0].options[0].value).toBe(
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
    );
    expect(
      result.current.modelProviderMapRef.current[
        PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO
      ],
    ).toBe(PROVIDER_TYPE.VERTEX_AI);
  });
});

describe("useModelOptions — a model the catalog serves on one OpenAI API", () => {
  const RESPONSES_ONLY = PROVIDER_MODEL_TYPE.GPT_5_5_PRO;
  const BOTH_APIS = PROVIDER_MODEL_TYPE.GPT_5_5;
  const MODELS = [
    { label: "GPT 5.5", value: BOTH_APIS },
    { label: "GPT 5.5 Pro", value: RESPONSES_ONLY },
  ];
  const PARAMS = new Map([
    [
      PROVIDER_TYPE.OPEN_AI as string,
      new Map([
        [BOTH_APIS as string, {}],
        [RESPONSES_ONLY as string, { only_route: "responses_api" }],
      ]),
    ],
  ]);

  const renderOptions = (
    configuration: Record<string, string>,
    provider = PROVIDER_TYPE.OPEN_AI,
  ) => {
    const providers = [createProvider(provider, configuration)];
    const providerModelsMap = { [provider]: MODELS };
    const { result } = renderHook(() =>
      useModelOptions(providers, providerModelsMap, ""),
    );
    return () => result.current.groupOptions[0].options.map((o) => o.value);
  };

  beforeEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each([
    ["no API set", {}],
    ["Chat Completions", { openai_pipeline_mode: "chat_completions_api" }],
  ])("hides it on a key with %s", (_, configuration) => {
    setLatestModelParams(PARAMS);

    expect(renderOptions(configuration)()).toEqual([BOTH_APIS]);
  });

  it.each(["responses_api", "RESPONSES_API"])(
    "shows it on a key set to %s",
    (mode) => {
      setLatestModelParams(PARAMS);

      expect(renderOptions({ openai_pipeline_mode: mode })()).toEqual([
        BOTH_APIS,
        RESPONSES_ONLY,
      ]);
    },
  );

  it("leaves another provider's list alone", () => {
    setLatestModelParams(PARAMS);

    expect(renderOptions({}, PROVIDER_TYPE.GEMINI)()).toEqual([
      BOTH_APIS,
      RESPONSES_ONLY,
    ]);
  });

  it("hides it once the catalog params arrive after the model list", () => {
    const optionValues = renderOptions({});
    expect(optionValues()).toEqual([BOTH_APIS, RESPONSES_ONLY]);

    act(() => setLatestModelParams(PARAMS));

    expect(optionValues()).toEqual([BOTH_APIS]);
  });
});
