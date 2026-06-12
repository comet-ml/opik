import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useLLMProviderModelsData from "./useLLMProviderModelsData";

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({
    data: {
      [PROVIDER_TYPE.GEMINI]: [
        {
          id: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
          label: "Gemini 2.5 Pro",
        },
      ],
      [PROVIDER_TYPE.VERTEX_AI]: [
        {
          id: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
          label: "Gemini 2.5 Pro",
        },
      ],
    },
    isPending: false,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/hooks/useOpenAICompatibleModels", () => ({
  default: () => ({}),
}));

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
});
