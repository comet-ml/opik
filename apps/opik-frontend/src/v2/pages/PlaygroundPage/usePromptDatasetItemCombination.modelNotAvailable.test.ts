import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import usePromptDatasetItemCombination from "./usePromptDatasetItemCombination";

const PROMPT_ID = "prompt-1";

const mocks = vi.hoisted(() => ({
  runStreaming: vi.fn(),
  updateOutput: vi.fn(),
}));

vi.mock("@/store/PlaygroundStore", () => ({
  default: { getState: () => ({ isRunningMap: { [PROMPT_ID]: true } }) },
  getExperimentNamesForPrompts: () => ({}),
  usePromptIds: () => [],
  usePromptMap: () => ({}),
  useUpdateOutput: () => mocks.updateOutput,
}));
vi.mock("@/api/playground/useCompletionProxyStreaming", () => ({
  default: () => mocks.runStreaming,
}));
vi.mock("@/v2/pages/PlaygroundPage/useHydrateDatasetItemData", () => ({
  useHydrateDatasetItemData: () => async () => ({}),
}));
vi.mock("@/v2/pages/PlaygroundPage/useHydratePromptMetadata", () => ({
  useHydratePromptMetadata: () => async () => undefined,
}));

const mythosPrompt: PlaygroundPromptType = {
  id: PROMPT_ID,
  name: "Prompt",
  model: PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_5,
  provider: PROVIDER_TYPE.ANTHROPIC,
  configs: {},
  messages: [{ id: "message-1", role: LLM_MESSAGE_ROLE.user, content: "Hi" }],
};

const notFoundRun: RunStreamingReturn = {
  result: "",
  startTime: "",
  endTime: "",
  usage: null,
  choices: null,
  providerError: "model: claude-mythos-5",
  opikError: null,
  pythonProxyError: null,
  errorStatus: 404,
  actualModel: PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_5,
  actualProvider: PROVIDER_TYPE.ANTHROPIC,
};

describe("usePromptDatasetItemCombination on a model the API key can't use", () => {
  it("stores the provider's error and the next step for the picked model", async () => {
    mocks.runStreaming.mockResolvedValue(notFoundRun);
    const { result } = renderHook(() =>
      usePromptDatasetItemCombination({
        datasetItems: [],
        workspaceName: "workspace",
        datasetName: null,
        selectedRuleIds: null,
        addAbortController: vi.fn(),
        deleteAbortController: vi.fn(),
        throttlingSeconds: 0,
      }),
    );

    await result.current.processCombination(
      { prompt: mythosPrompt },
      { log: vi.fn(), finishLogging: vi.fn() },
    );

    expect(mocks.updateOutput).toHaveBeenLastCalledWith(PROMPT_ID, "", {
      isLoading: false,
      error: "model: claude-mythos-5",
      errorHint: {
        action:
          "This model isn't available for your API key. Pick another model, or check which models your provider account can use.",
      },
    });
  });
});
