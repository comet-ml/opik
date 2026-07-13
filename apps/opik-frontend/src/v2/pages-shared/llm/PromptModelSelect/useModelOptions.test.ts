import { describe, expect, it } from "vitest";
import { renderHook } from "@testing-library/react";

import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";
import { sortProviderModels, useModelOptions } from "./useModelOptions";

const createProvider = (provider: PROVIDER_TYPE): ProviderObject =>
  ({
    id: provider,
    created_at: "",
    provider,
    ui_composed_provider: provider,
    configuration: {},
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

  it("sorts OrcaRouter models with bare routers first and A-Z", () => {
    const models = [
      { label: "orcarouter/openai/gpt-4o-mini", value: "orcarouter/openai/gpt-4o-mini" },
      { label: "orcarouter/auto", value: "orcarouter/auto" },
      { label: "orcarouter/anthropic/claude-opus-4.8", value: "orcarouter/anthropic/claude-opus-4.8" },
    ];

    expect(sortProviderModels(PROVIDER_TYPE.ORCA_ROUTER, models)).toEqual([
      { label: "orcarouter/auto", value: "orcarouter/auto" },
      {
        label: "orcarouter/anthropic/claude-opus-4.8",
        value: "orcarouter/anthropic/claude-opus-4.8",
      },
      {
        label: "orcarouter/openai/gpt-4o-mini",
        value: "orcarouter/openai/gpt-4o-mini",
      },
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
