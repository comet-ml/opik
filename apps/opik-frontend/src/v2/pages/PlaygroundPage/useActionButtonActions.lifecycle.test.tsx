import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import React, { act } from "react";
import { renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { EXPERIMENT_POLL_INTERVAL_MS } from "@/constants/experiments";
import { EXPERIMENT_STATUS } from "@/types/datasets";
import usePlaygroundStore from "@/store/PlaygroundStore";
import useActionButtonActions from "./useActionButtonActions";

const cancelExperimentRun = vi.fn();

vi.mock("@/api/playground/useCancelExperimentExecution", () => ({
  default: () => ({ mutate: cancelExperimentRun }),
}));

const runExperimentExecution = vi.fn();
vi.mock("@/api/playground/useRunExperimentExecution", () => ({
  default: () => ({ mutateAsync: runExperimentExecution }),
}));

const getExperimentById = vi.fn();
vi.mock("@/api/datasets/useExperimentById", () => ({
  getExperimentById: (...args: unknown[]) => getExperimentById(...args),
  default: () => ({}),
}));

vi.mock("@/v2/pages/PlaygroundPage/usePromptCombination", () => ({
  default: () => ({
    createCombinations: vi.fn(),
    processCombination: vi.fn(),
  }),
}));

vi.mock("@/v2/pages/PlaygroundPage/useRunCompletionToast", () => ({
  default: () => vi.fn(),
}));

vi.mock("@/hooks/useOpenAiPipelineMode", () => ({
  default: () => undefined,
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canLogTraceSpanThread: true } }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

const PROMPT_A = "prompt-a";
const PROMPT_B = "prompt-b";
const EXPERIMENT_A = "experiment-a";
const EXPERIMENT_B = "experiment-b";

const renderActions = (datasetId?: string) =>
  renderHook(
    () =>
      useActionButtonActions({
        workspaceName: "ws",
        datasetName: datasetId ? "dataset" : null,
        datasetId,
      }),
    {
      wrapper: ({ children }) =>
        React.createElement(
          QueryClientProvider,
          { client: new QueryClient() },
          children,
        ),
    },
  );

// A run the server owns: both prompts dispatched, each with an experiment to cancel.
const startBackendRun = () =>
  usePlaygroundStore.setState({
    promptIds: [PROMPT_A, PROMPT_B],
    isRunningMap: { [PROMPT_A]: true, [PROMPT_B]: true },
    isRunInFlight: true,
    experimentByPromptId: {
      [PROMPT_A]: EXPERIMENT_A,
      [PROMPT_B]: EXPERIMENT_B,
    },
  });

beforeEach(() => {
  cancelExperimentRun.mockClear();
  usePlaygroundStore.setState({
    promptIds: [],
    isRunningMap: {},
    isRunInFlight: false,
    isResumingRun: false,
    experimentByPromptId: {},
  });
});

describe("useActionButtonActions lifecycle", () => {
  // Leaving the page and stopping the run are different intents, and the whole point of moving
  // execution to the server is that the first one does not end the run.
  describe("stopWatching, the page going away", () => {
    it("should not cancel the run on the server", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopWatching());

      expect(cancelExperimentRun).not.toHaveBeenCalled();
    });

    it("should leave the run in flight so the sidebar keeps watching it", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopWatching());

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(true);
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({
        [PROMPT_A]: true,
        [PROMPT_B]: true,
      });
    });

    // Without a dataset the run is this tab's own work, so leaving really does end it.
    it("should settle a run executing in the browser, which the page cannot outlive", () => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A],
        isRunningMap: { [PROMPT_A]: true },
        isRunInFlight: true,
      });
      const { result } = renderActions(undefined);

      act(() => result.current.stopWatching());

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(false);
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({});
    });
  });

  describe("stopAll, the user stopping the run", () => {
    it("should cancel every experiment on the server", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopAll());

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A, EXPERIMENT_B],
      });
    });

    it("should settle the run rather than leave it watched", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopAll());

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(false);
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({});
    });

    it("should not reach the server for a run executing in the browser", () => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A],
        isRunningMap: { [PROMPT_A]: true },
        isRunInFlight: true,
        experimentByPromptId: { [PROMPT_A]: EXPERIMENT_A },
      });
      const { result } = renderActions(undefined);

      act(() => result.current.stopAll());

      expect(cancelExperimentRun).not.toHaveBeenCalled();
    });
  });

  describe("stopSingle, one prompt of several", () => {
    it("should cancel only that prompt's experiment", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopSingle(PROMPT_A));

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A],
      });
    });

    it("should leave its sibling running", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopSingle(PROMPT_A));

      expect(usePlaygroundStore.getState().isRunningMap).toEqual({
        [PROMPT_A]: false,
        [PROMPT_B]: true,
      });
    });

    // The sidebar watches the run through isRunInFlight, so clearing it here cost the sibling its
    // completion dot: leaving the page started no watcher and nothing noticed it finish.
    it("should keep the run in flight so the sibling still earns its dot", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopSingle(PROMPT_A));

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(true);
    });

    it("should settle once the last running prompt is cancelled", () => {
      startBackendRun();
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopSingle(PROMPT_A));
      act(() => result.current.stopSingle(PROMPT_B));

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(false);
    });
  });

  // The other way a scope ends: its poll sees the experiment settle, rather than the user
  // cancelling it. Same shared flag, reached through different code.
  describe("two scoped runs completing in sequence", () => {
    const runScoped = async (
      runSingle: (promptId: string) => Promise<void>,
      promptId: string,
      experimentId: string,
    ) => {
      runExperimentExecution.mockResolvedValue({
        experiments: [{ experiment_id: experimentId }],
        total_items: 1,
      });
      await act(async () => {
        await runSingle(promptId);
      });
      // The run schedules its first poll rather than running it, so the clock has to move before
      // the experiment is read. One pass is enough: it comes back already terminal.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS);
      });
    };

    beforeEach(() => {
      vi.useFakeTimers();
      runExperimentExecution.mockReset();
      getExperimentById.mockReset();
      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.COMPLETED,
        trace_count: 1,
      });
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
        isRunningMap: { [PROMPT_A]: true, [PROMPT_B]: true },
        isRunInFlight: true,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should keep the run in flight after the first scope settles", async () => {
      const { result } = renderActions("dataset-1");

      await runScoped(result.current.runSingle, PROMPT_A, EXPERIMENT_A);

      expect(usePlaygroundStore.getState().isRunningMap[PROMPT_A]).toBe(false);
      expect(usePlaygroundStore.getState().isRunInFlight).toBe(true);
    });

    it("should clear it only once the second scope settles too", async () => {
      const { result } = renderActions("dataset-1");

      await runScoped(result.current.runSingle, PROMPT_A, EXPERIMENT_A);
      await runScoped(result.current.runSingle, PROMPT_B, EXPERIMENT_B);

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(false);
    });
  });
});
