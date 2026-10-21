import React from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import usePlaygroundStore from "@/store/PlaygroundStore";
import useActionButtonActions from "@/v2/pages/PlaygroundPage/useActionButtonActions";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import { getCompareExperimentsList } from "@/api/datasets/useCompareExperimentsList";
import {
  DATASET_TYPE,
  EXPERIMENT_STATUS,
  Experiment,
  ExperimentsCompare,
} from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

const TOTAL_ITEMS = 4;
const EXPERIMENT_ID = "experiment-1";
const PROMPT_ID = "prompt-1";

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

const runningExperiment = (traceCount: number) =>
  ({
    id: EXPERIMENT_ID,
    trace_count: traceCount,
    status: EXPERIMENT_STATUS.RUNNING,
  }) as Experiment;

const compareRows = (statuses: (string | null)[]) => ({
  content: statuses.map(
    (status, index) =>
      ({
        id: `item-${index}`,
        evaluators: [{ name: "assertions" }],
        experiment_items: [{ id: `experiment-item-${index}`, status }],
      }) as unknown as ExperimentsCompare,
  ),
  total: statuses.length,
});

const getProgress = () => {
  const { progressPhase, progressCompleted, progressTotal } =
    usePlaygroundStore.getState();
  return { progressPhase, progressCompleted, progressTotal };
};

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

const runAll = (result: ReturnType<typeof renderActions>) =>
  act(async () => {
    await result.current.runAll();
  });

const startTestSuiteRun = () => runAll(renderActions());

const nextPoll = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

describe("useActionButtonActions test-suite progress", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mutateAsync.mockResolvedValue({
      experiments: [{ experiment_id: EXPERIMENT_ID, prompt_index: 0 }],
      total_items: TOTAL_ITEMS,
    });

    const prompt = {
      id: PROMPT_ID,
      name: "Prompt",
      messages: [],
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      provider: PROVIDER_TYPE.OPEN_AI,
      configs: {},
    } as unknown as PlaygroundPromptType;
    const { setPromptMap, setDatasetType } = usePlaygroundStore.getState();
    setPromptMap([PROMPT_ID], { [PROMPT_ID]: prompt });
    setDatasetType(DATASET_TYPE.TEST_SUITE);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.mocked(getExperimentById).mockReset();
    vi.mocked(getCompareExperimentsList).mockReset();
  });

  it("should keep step 1 at the highest trace count when a later poll reads fewer traces", async () => {
    vi.mocked(getExperimentById)
      .mockResolvedValueOnce(runningExperiment(3))
      .mockResolvedValueOnce(runningExperiment(1));

    await startTestSuiteRun();
    await nextPoll(2000);
    await nextPoll(2000);

    expect(getProgress()).toEqual({
      progressPhase: "running",
      progressCompleted: 3,
      progressTotal: TOTAL_ITEMS,
    });
  });

  it("should count step 2 against the run's total, not the rows seen so far", async () => {
    vi.mocked(getExperimentById).mockResolvedValue(
      runningExperiment(TOTAL_ITEMS),
    );
    vi.mocked(getCompareExperimentsList).mockResolvedValueOnce(
      compareRows(["passed", null]),
    );

    await startTestSuiteRun();
    await nextPoll(2000);

    expect(getProgress()).toEqual({
      progressPhase: "evaluating",
      progressCompleted: 1,
      progressTotal: TOTAL_ITEMS,
    });
  });

  it("should keep step 2 at the highest scored count when a later poll reads fewer scores", async () => {
    vi.mocked(getExperimentById).mockResolvedValue(
      runningExperiment(TOTAL_ITEMS),
    );
    vi.mocked(getCompareExperimentsList)
      .mockResolvedValueOnce(compareRows(["passed", "passed", "failed", null]))
      .mockResolvedValueOnce(compareRows(["passed", null, null, null]));

    await startTestSuiteRun();
    await nextPoll(2000);
    await nextPoll(1000);

    expect(getProgress()).toMatchObject({
      progressPhase: "evaluating",
      progressCompleted: 3,
    });
  });

  it("should ignore a stopped run's poll after Run is clicked again", async () => {
    vi.mocked(getExperimentById).mockImplementation(
      async (_, { experimentId }) =>
        experimentId === EXPERIMENT_ID
          ? runningExperiment(3)
          : { ...runningExperiment(1), id: experimentId },
    );
    const result = renderActions();
    await runAll(result);
    act(() => result.current.stopAll());

    mutateAsync.mockResolvedValueOnce({
      experiments: [{ experiment_id: "experiment-2", prompt_index: 0 }],
      total_items: 2,
    });
    await runAll(result);
    await nextPoll(2000);

    expect(getExperimentById).not.toHaveBeenCalledWith(expect.anything(), {
      experimentId: EXPERIMENT_ID,
    });
    expect(getProgress()).toEqual({
      progressPhase: "running",
      progressCompleted: 1,
      progressTotal: 2,
    });
  });
});
