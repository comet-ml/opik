import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import useModelSelection from "./useModelSelection";

const PERSISTENCE_KEY = "test-last-picked-model";

const registry = vi.hoisted(() => {
  const model = (id: string, label?: string) => ({
    id,
    label,
    structuredOutput: false,
    reasoning: false,
  });
  return {
    data: {
      openai: [
        model("gpt-5.5", "GPT 5.5"),
        model("gpt-4o-mini", "GPT 4o Mini"),
        model("gpt-live-1"),
      ],
      gemini: [
        model("gemini-2.5-flash", "Gemini 2.5 Flash"),
        model("gemini-omni-1.1-flash"),
        model("lyria-3.5"),
      ],
    } as LlmModelsByProvider,
  };
});

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

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "default" })),
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({
    data: {
      content: [
        { ui_composed_provider: PROVIDER_TYPE.OPEN_AI },
        { ui_composed_provider: PROVIDER_TYPE.GEMINI },
      ],
    },
  }),
}));

describe("useModelSelection", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it.each<[string, PROVIDER_MODEL_TYPE, PROVIDER_MODEL_TYPE, PROVIDER_TYPE]>([
    [
      "starts on a remembered pick from the picker",
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      PROVIDER_TYPE.GEMINI,
    ],
    [
      "ignores a remembered OpenAI pick that left the picker",
      PROVIDER_MODEL_TYPE.GPT_LIVE_1,
      PROVIDER_MODEL_TYPE.GPT_5_5,
      PROVIDER_TYPE.OPEN_AI,
    ],
    [
      "ignores a remembered Gemini pick that left the picker",
      PROVIDER_MODEL_TYPE.GEMINI_OMNI_1_1_FLASH,
      PROVIDER_MODEL_TYPE.GPT_5_5,
      PROVIDER_TYPE.OPEN_AI,
    ],
    [
      "ignores a remembered music model",
      PROVIDER_MODEL_TYPE.LYRIA_3_5,
      PROVIDER_MODEL_TYPE.GPT_5_5,
      PROVIDER_TYPE.OPEN_AI,
    ],
  ])("%s", (_, lastPicked, expectedModel, expectedProvider) => {
    localStorage.setItem(PERSISTENCE_KEY, JSON.stringify(lastPicked));

    const { result } = renderHook(() =>
      useModelSelection({ persistenceKey: PERSISTENCE_KEY }),
    );

    expect(result.current.model).toBe(expectedModel);
    expect(result.current.provider).toBe(expectedProvider);
  });

  it("falls back to the caller's default model over a hidden remembered pick", () => {
    localStorage.setItem(
      PERSISTENCE_KEY,
      JSON.stringify(PROVIDER_MODEL_TYPE.GPT_LIVE_1),
    );

    const { result } = renderHook(() =>
      useModelSelection({
        persistenceKey: PERSISTENCE_KEY,
        defaultModel: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
        defaultProvider: PROVIDER_TYPE.GEMINI,
      }),
    );

    expect(result.current.model).toBe(PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH);
    expect(result.current.provider).toBe(PROVIDER_TYPE.GEMINI);
  });
});
