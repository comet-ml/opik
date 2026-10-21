import React from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import usePlaygroundStore from "@/store/PlaygroundStore";
import useActionButtonActions from "@/v2/pages/PlaygroundPage/useActionButtonActions";
import { DATASET_TYPE } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

const PROMPT_A = "prompt-a";
const PROMPT_B = "prompt-b";

const mutateAsync = vi.fn();

vi.mock("@/api/datasets/useExperimentById", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  getExperimentById: vi.fn(),
}));

vi.mock("@/api/datasets/useCompareExperimentsList", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  getCompareExperimentsList: vi.fn(),
}));

vi.mock("@/api/playground/useRunExperimentExecution", () => ({
  default: () => ({ mutateAsync }),
}));

vi.mock("@/hooks/useOpenAiPipelineMode", () => ({
  default: () => undefined,
}));

vi.mock("@/v2/pages/PlaygroundPage/useRunCompletionToast", () => ({
  default: () => vi.fn(),
}));

vi.mock("@/v2/pages/PlaygroundPage/usePromptDatasetItemCombination", () => ({
  default: () => ({
    createCombinations: () => [],
    processCombination: vi.fn(),
  }),
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: { canLogTraceSpanThread: true, canCreateExperiments: true },
  }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

const createPrompt = (id: string) =>
  ({
    id,
    name: id,
    messages: [],
    model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
    provider: PROVIDER_TYPE.OPEN_AI,
    configs: {},
  }) as unknown as PlaygroundPromptType;

const renderActions = () => {
  const queryClient = new QueryClient();
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(
    () =>
      useActionButtonActions({
        datasetItems: [],
        workspaceName: "default",
        datasetName: "suite",
        datasetId: "suite-id",
      }),
    { wrapper },
  );
  return result;
};

describe("useActionButtonActions single test-suite run", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mutateAsync.mockResolvedValue({
      experiments: [{ experiment_id: "experiment-b2", prompt_index: 0 }],
      total_items: 1,
    });

    const state = usePlaygroundStore.getState();
    state.setPromptMap([PROMPT_A, PROMPT_B], {
      [PROMPT_A]: createPrompt(PROMPT_A),
      [PROMPT_B]: createPrompt(PROMPT_B),
    });
    state.setDatasetType(DATASET_TYPE.TEST_SUITE);
    state.setExperimentByPromptId({
      [PROMPT_A]: "experiment-a1",
      [PROMPT_B]: "experiment-b1",
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("should keep the other variant on the run it was scored in", async () => {
    const result = renderActions();

    await act(async () => {
      await result.current.runSingle(PROMPT_B);
    });

    const { experimentByPromptId, runExperimentIdsByPromptId } =
      usePlaygroundStore.getState();
    expect(experimentByPromptId).toEqual({
      [PROMPT_A]: "experiment-a1",
      [PROMPT_B]: "experiment-b2",
    });
    expect(runExperimentIdsByPromptId).toEqual({
      [PROMPT_A]: ["experiment-a1", "experiment-b1"],
      [PROMPT_B]: ["experiment-b2"],
    });
  });
});
