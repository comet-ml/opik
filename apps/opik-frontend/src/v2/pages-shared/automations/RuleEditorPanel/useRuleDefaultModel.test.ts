import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import {
  RULE_DEFAULT_MODEL_BY_PROVIDER,
  RULE_LAST_PICKED_MODEL,
} from "./constants";
import useRuleDefaultModel from "./useRuleDefaultModel";

const registry = vi.hoisted(() => {
  const model = (id: string, label?: string) => ({
    id,
    label,
    structured_output: false,
    reasoning: false,
  });
  return {
    data: {
      openai: [
        model("gpt-5.5", "GPT 5.5"),
        model("gpt-4o-mini", "GPT 4o Mini"),
        model("gpt-live-1"),
      ],
    } as LlmModelsByProvider,
  };
});

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({
    data: registry.data,
    isPending: false,
    isFetched: true,
    isError: false,
    error: null,
  }),
}));

vi.mock("@/api/llm/useLlmModelProfiles", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "default" })),
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => {
  const data = {
    content: [
      {
        provider: PROVIDER_TYPE.OPEN_AI,
        ui_composed_provider: PROVIDER_TYPE.OPEN_AI,
      },
    ],
  };
  return { default: () => ({ data, isPending: false }) };
});

describe("useRuleDefaultModel", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it.each<[string, PROVIDER_MODEL_TYPE, PROVIDER_MODEL_TYPE | undefined]>([
    [
      "starts on a remembered pick from the picker",
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
    ],
    [
      "ignores a remembered pick that left the picker",
      PROVIDER_MODEL_TYPE.GPT_LIVE_1,
      RULE_DEFAULT_MODEL_BY_PROVIDER[PROVIDER_TYPE.OPEN_AI],
    ],
  ])("%s", (_, lastPicked, expected) => {
    localStorage.setItem(RULE_LAST_PICKED_MODEL, JSON.stringify(lastPicked));

    const { result } = renderHook(() => useRuleDefaultModel());

    expect(result.current.defaultModel).toEqual({
      model: expected,
      provider: PROVIDER_TYPE.OPEN_AI,
    });
  });
});
