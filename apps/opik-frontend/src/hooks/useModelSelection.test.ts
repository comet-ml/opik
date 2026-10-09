import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import useModelSelection from "./useModelSelection";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const state = vi.hoisted(() => ({ lastPicked: "" }));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [state.lastPicked, vi.fn()],
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (s: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "default" }),
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({
    data: {
      content: [
        { ui_composed_provider: PROVIDER_TYPE.OPEN_AI },
        { ui_composed_provider: PROVIDER_TYPE.ANTHROPIC },
      ],
    },
  }),
}));

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: (model: string) =>
      model.startsWith("claude")
        ? PROVIDER_TYPE.ANTHROPIC
        : model
          ? PROVIDER_TYPE.OPEN_AI
          : "",
    calculateDefaultModel: () => PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  }),
}));

beforeEach(() => {
  state.lastPicked = "";
});

describe("useModelSelection", () => {
  it.each([
    {
      name: "the caller's model, before any pick",
      lastPicked: "",
      defaultModel: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      defaultProvider: PROVIDER_TYPE.OPEN_AI,
      expectedModel: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      expectedProvider: PROVIDER_TYPE.OPEN_AI,
    },
    {
      name: "a picked model",
      lastPicked: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      defaultModel: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      defaultProvider: PROVIDER_TYPE.OPEN_AI,
      expectedModel: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      expectedProvider: PROVIDER_TYPE.ANTHROPIC,
    },
    {
      name: "the workspace default, with no caller model",
      lastPicked: "",
      defaultModel: undefined,
      defaultProvider: undefined,
      expectedModel: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      expectedProvider: PROVIDER_TYPE.OPEN_AI,
    },
  ])(
    "runs $name at that model's defaults",
    ({
      lastPicked,
      defaultModel,
      defaultProvider,
      expectedModel,
      expectedProvider,
    }) => {
      state.lastPicked = lastPicked;

      const { result } = renderHook(() =>
        useModelSelection({
          persistenceKey: "test-model",
          defaultModel,
          defaultProvider: defaultProvider as COMPOSED_PROVIDER_TYPE,
        }),
      );

      expect(result.current.model).toBe(expectedModel);
      expect(result.current.configs).toEqual(
        getDefaultConfigByProvider(
          expectedProvider as COMPOSED_PROVIDER_TYPE,
          expectedModel,
        ),
      );
    },
  );
});
