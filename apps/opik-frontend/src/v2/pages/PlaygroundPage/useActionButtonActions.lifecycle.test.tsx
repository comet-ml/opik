import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import React, { act } from "react";
import { renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { EXPERIMENT_POLL_INTERVAL_MS } from "@/constants/experiments";
import { EXPERIMENT_STATUS, DATASET_TYPE } from "@/types/datasets";
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

// Module-scope spy: vi.mock factories run lazily, after this is initialized. Hoisted out of the
// factory so a test can read what was actually shown.
const toastSpy = vi.fn();

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: toastSpy }),
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
    promptMap: {},
    isRunningMap: {},
    isRunInFlight: false,
    isResumingRun: false,
    experimentByPromptId: {},
    runTotalItemsByPromptId: {},
    watchLapsed: new Set<string>(),
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

  // A single-prompt run polls with a scope, and a scoped poll ignores isToStopRef on purpose, so
  // that stopping one prompt leaves its siblings watching. Nothing else reached it, so its timer
  // chain outlived the page: an orphan still reading experiments, and a second one beside it after
  // the next run started.
  describe("a scoped run the page stops following", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      runExperimentExecution.mockReset();
      runExperimentExecution.mockResolvedValue({
        experiments: [{ experiment_id: EXPERIMENT_A }],
        total_items: 1,
      });
      getExperimentById.mockReset();
      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.RUNNING,
        trace_count: 0,
      });
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    const watchUntilItPolls = async () => {
      const rendered = renderActions("dataset-1");
      await act(async () => {
        await rendered.result.current.runSingle(PROMPT_A);
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS);
      });
      expect(getExperimentById).toHaveBeenCalled();
      getExperimentById.mockClear();
      return rendered;
    };

    const keepTheClockRunning = async () => {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS * 5);
      });
    };

    it("should stop reading experiments once the page leaves", async () => {
      const { result } = await watchUntilItPolls();

      act(() => result.current.stopWatching());
      await keepTheClockRunning();

      expect(getExperimentById).not.toHaveBeenCalled();
    });

    it("should stop reading experiments once the user stops everything", async () => {
      const { result } = await watchUntilItPolls();

      act(() => result.current.stopAll());
      await keepTheClockRunning();

      expect(getExperimentById).not.toHaveBeenCalled();
    });
  });

  // Keyed by prompt and taken per variant. Keying by experiment id instead leaves every lookup in
  // the resume missing, and dividing the batch total assumes every variant ran the same items.
  describe("recording what a run dispatched", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
        },
      } as unknown as Parameters<typeof usePlaygroundStore.setState>[0]);
      getExperimentById.mockResolvedValue({
        id: EXPERIMENT_A,
        status: "running",
        trace_count: 0,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should store each variant's own total against its prompt", async () => {
      runExperimentExecution.mockResolvedValue({
        experiments: [
          { experiment_id: EXPERIMENT_A, prompt_index: 0, total_items: 300 },
          { experiment_id: EXPERIMENT_B, prompt_index: 1, total_items: 300 },
        ],
        total_items: 600,
      });

      const { result } = renderActions("dataset-1");
      await act(async () => {
        await result.current.runAll();
      });

      expect(usePlaygroundStore.getState().runTotalItemsByPromptId).toEqual({
        [PROMPT_A]: 300,
        [PROMPT_B]: 300,
      });
    });
  });

  describe("resuming after a reload", () => {
    beforeEach(() => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        experimentByPromptId: {
          [PROMPT_A]: EXPERIMENT_A,
          [PROMPT_B]: EXPERIMENT_B,
        },
        runTotalItemsByPromptId: { [PROMPT_A]: 1_000, [PROMPT_B]: 1_000 },
      });
    });

    it("should measure progress against only the prompts it is resuming", async () => {
      // A finished, B still going: B's own total is what is left to do.
      getExperimentById.mockImplementation((_ctx, { experimentId }) =>
        Promise.resolve(
          experimentId === EXPERIMENT_A
            ? { id: EXPERIMENT_A, status: "completed", trace_count: 1_000 }
            : { id: EXPERIMENT_B, status: "running", trace_count: 400 },
        ),
      );

      await act(async () => {
        renderActions("dataset-1");
      });
      await act(async () => {
        await Promise.resolve();
      });

      expect(usePlaygroundStore.getState().progressTotal).toBe(1_000);
      expect(usePlaygroundStore.getState().progressCompleted).toBe(400);
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({
        [PROMPT_B]: true,
      });
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

      // A is gone from the map rather than recorded as stopped; B is still running, which is why
      // the run as a whole stays in flight.
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({
        [PROMPT_B]: true,
      });
      expect(usePlaygroundStore.getState().isRunInFlight).toBe(true);
    });

    it("should clear it only once the second scope settles too", async () => {
      const { result } = renderActions("dataset-1");

      await runScoped(result.current.runSingle, PROMPT_A, EXPERIMENT_A);
      await runScoped(result.current.runSingle, PROMPT_B, EXPERIMENT_B);

      expect(usePlaygroundStore.getState().isRunInFlight).toBe(false);
    });
  });

  // Stop pressed between Run and the server answering. There is nothing to cancel by id yet, so the
  // old code returned and the run carried on — started, unstoppable from here, and unwatched.
  describe("stopped before the server names the run", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      getExperimentById.mockReset();
      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.RUNNING,
        trace_count: 0,
      });
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should cancel the run as soon as the response names it", async () => {
      let answer: (value: unknown) => void = () => {};
      runExperimentExecution.mockReturnValue(
        new Promise((resolve) => {
          answer = resolve;
        }),
      );

      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runAll();
      });

      // Stop lands while the request is still open: no ids exist anywhere yet.
      act(() => result.current.stopAll());
      expect(cancelExperimentRun).not.toHaveBeenCalled();

      await act(async () => {
        answer({
          experiments: [
            { experiment_id: EXPERIMENT_A, prompt_index: 0, total_items: 5 },
          ],
          total_items: 5,
        });
        await running;
      });

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A],
      });
      expect(usePlaygroundStore.getState().isRunInFlight).toBe(false);
    });

    // Re-running one prompt is the common case, and the id of its previous run is still in the
    // store. A stop in the launch window must reach the run that is going, not the one that ended.
    it("should cancel the new run rather than the one the prompt ran last", async () => {
      usePlaygroundStore.setState({
        experimentByPromptId: { [PROMPT_A]: EXPERIMENT_B },
      });

      let answer: (value: unknown) => void = () => {};
      runExperimentExecution.mockReturnValue(
        new Promise((resolve) => {
          answer = resolve;
        }),
      );

      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runSingle(PROMPT_A);
      });

      act(() => result.current.stopSingle(PROMPT_A));
      expect(cancelExperimentRun).not.toHaveBeenCalled();

      await act(async () => {
        answer({
          experiments: [
            { experiment_id: EXPERIMENT_A, prompt_index: 0, total_items: 5 },
          ],
          total_items: 5,
        });
        await running;
      });

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A],
      });
    });

    // A stop waits on ids the launch was going to bring. When the launch fails instead they never
    // come, and a stop left queued holds the prompt on a Stopping it can never finish.
    it("should let the prompt run again when the launch it was stopping never started", async () => {
      let fail: (reason: unknown) => void = () => {};
      runExperimentExecution.mockReturnValue(
        new Promise((_, reject) => {
          fail = reject;
        }),
      );

      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runSingle(PROMPT_A);
      });

      act(() => result.current.stopSingle(PROMPT_A));
      expect(usePlaygroundStore.getState().stopPending).toEqual(
        new Set([PROMPT_A]),
      );

      await act(async () => {
        fail(new Error("execute never answered"));
        await running;
      });

      expect(usePlaygroundStore.getState().stopPending.size).toBe(0);
    });

    // Each prompt launches on its own request, so one failing says nothing about the other. Its
    // sibling's stop is still waiting on a response that is still coming.
    it("should keep a sibling's stop when only one launch fails", async () => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });

      let failA: (reason: unknown) => void = () => {};
      runExperimentExecution
        .mockReturnValueOnce(
          new Promise((_, reject) => {
            failA = reject;
          }),
        )
        .mockReturnValueOnce(new Promise(() => {}));

      const { result } = renderActions("dataset-1");

      let runningA: Promise<void>;
      await act(async () => {
        runningA = result.current.runSingle(PROMPT_A);
        result.current.runSingle(PROMPT_B);
      });

      act(() => result.current.stopSingle(PROMPT_A));
      act(() => result.current.stopSingle(PROMPT_B));

      await act(async () => {
        failA(new Error("execute never answered"));
        await runningA;
      });

      expect(usePlaygroundStore.getState().stopPending).toEqual(
        new Set([PROMPT_B]),
      );
    });

    const twoPromptsLaunching = () => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });

      let answer: (value: unknown) => void = () => {};
      runExperimentExecution.mockReturnValue(
        new Promise((resolve) => {
          answer = resolve;
        }),
      );
      return () =>
        answer({
          experiments: [
            { experiment_id: EXPERIMENT_A, prompt_index: 0, total_items: 5 },
            { experiment_id: EXPERIMENT_B, prompt_index: 1, total_items: 5 },
          ],
          total_items: 10,
        });
    };

    // A stop aimed at one prompt is not a stop of the batch it was launched with. Settling the
    // whole run on it leaves its siblings running on the server with nothing watching them, and
    // their Run buttons back, so the next click dispatches the dataset again alongside them.
    it("should keep watching the prompts a single stop did not name", async () => {
      const answerBoth = twoPromptsLaunching();
      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runAll();
      });

      act(() => result.current.stopSingle(PROMPT_A));

      await act(async () => {
        answerBoth();
        await running;
      });

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A],
      });
      expect(usePlaygroundStore.getState().isRunInFlight).toBe(true);
    });

    // Stopping one prompt takes it out of the running map while its own stop is still queued, so
    // a Stop all that reads only that map cannot see it, and settling forgets it.
    it("should carry a prompt's queued stop into Stop all", async () => {
      const answerBoth = twoPromptsLaunching();
      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runAll();
      });

      act(() => result.current.stopSingle(PROMPT_A));
      act(() => result.current.stopAll());

      await act(async () => {
        answerBoth();
        await running;
      });

      // Order is not part of the contract; both have to be in the one cancel.
      const lastCall = cancelExperimentRun.mock.calls.at(-1);
      const { experimentIds } = lastCall![0];
      expect([...experimentIds].sort()).toEqual([EXPERIMENT_A, EXPERIMENT_B]);
    });

    // The mapping the cell reads is persisted, so a launch that cleared it and then failed takes
    // the last run off the screen for good rather than until the next load.
    it("should keep the last run's results when a rerun never starts", async () => {
      usePlaygroundStore.setState({
        experimentByPromptId: { [PROMPT_A]: EXPERIMENT_B },
      });

      let fail: (reason: unknown) => void = () => {};
      runExperimentExecution.mockReturnValue(
        new Promise((_, reject) => {
          fail = reject;
        }),
      );

      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runSingle(PROMPT_A);
      });
      expect(usePlaygroundStore.getState().experimentByPromptId).toEqual({});

      await act(async () => {
        fail(new Error("execute never answered"));
        await running;
      });

      expect(usePlaygroundStore.getState().experimentByPromptId).toEqual({
        [PROMPT_A]: EXPERIMENT_B,
      });
    });

    // An empty set is not null, so every reader takes it for a stop still waiting and the
    // controls never come back.
    it("should leave no stop waiting when there was nothing to stop", async () => {
      const { result } = renderActions("dataset-1");

      act(() => result.current.stopAll());

      expect(usePlaygroundStore.getState().stopPending.size).toBe(0);
    });

    // A stop can reach prompts in both states at once: one whose id arrived long ago, one still
    // waiting on its launch. Treating the set as all-or-nothing cancels the first and abandons
    // the second, which then runs to completion with nothing left that can name it.
    it("should cancel the prompts it can name and queue the ones it cannot", async () => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
        // A finished its run earlier, so only it has an id.
        experimentByPromptId: { [PROMPT_A]: EXPERIMENT_A },
        isRunningMap: { [PROMPT_A]: true, [PROMPT_B]: true },
      });

      const { result } = renderActions("dataset-1");

      act(() => result.current.stopAll());

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A],
      });
      expect(usePlaygroundStore.getState().stopPending).toEqual(
        new Set([PROMPT_B]),
      );
    });

    // Two prompts stopped one after the other in the same window. Both are waiting on the same
    // response, so the second must not take the first one's place in the queue.
    it("should keep both stops when each prompt is stopped on its own", async () => {
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });

      let answer: (value: unknown) => void = () => {};
      runExperimentExecution.mockReturnValue(
        new Promise((resolve) => {
          answer = resolve;
        }),
      );

      const { result } = renderActions("dataset-1");

      let running: Promise<void>;
      await act(async () => {
        running = result.current.runAll();
      });

      act(() => result.current.stopSingle(PROMPT_A));
      act(() => result.current.stopSingle(PROMPT_B));

      await act(async () => {
        answer({
          experiments: [
            { experiment_id: EXPERIMENT_A, prompt_index: 0, total_items: 5 },
            { experiment_id: EXPERIMENT_B, prompt_index: 1, total_items: 5 },
          ],
          total_items: 10,
        });
        await running;
      });

      expect(cancelExperimentRun).toHaveBeenCalledWith({
        experimentIds: [EXPERIMENT_A, EXPERIMENT_B],
      });
    });
  });

  // A poll that fails had a reason, and the backend usually says what it was. Showing only the
  // caller's "Failed to poll …" line leaves the user with no way to tell a permission problem
  // from an outage.
  describe("a poll the server refuses", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      toastSpy.mockClear();
      runExperimentExecution.mockReset();
      runExperimentExecution.mockResolvedValue({
        experiments: [{ experiment_id: EXPERIMENT_A, prompt_index: 0 }],
        total_items: 1,
      });
      getExperimentById.mockReset();
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    const failPollWith = async (error: unknown) => {
      getExperimentById.mockRejectedValue(error);
      const { result } = renderActions("dataset-1");
      await act(async () => {
        await result.current.runAll();
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS);
      });
      return toastSpy.mock.calls.at(-1)?.[0];
    };

    it("should show what the server said alongside the read that failed", async () => {
      const shown = await failPollWith({
        response: { data: { message: "Workspace not found" } },
      });

      expect(shown?.description).toContain("Workspace not found");
      expect(shown?.description).toContain("Failed to poll experiment");
    });

    it("should fall back to something sayable when the error explains nothing", async () => {
      const shown = await failPollWith(new Error("Network Error"));

      expect(shown?.description).toContain("Network Error");
    });
  });

  // One prompt's poll giving up used to pause the whole run, because the generation it moves and
  // the paused flag it sets are both the run's rather than the scope's. Its siblings were left
  // stuck on Stop even after their own runs finished.
  describe("a single prompt's poll outliving the watch window", () => {
    const PAST_THE_WATCH_WINDOW = 6 * 60 * 1000;

    beforeEach(() => {
      vi.useFakeTimers();
      runExperimentExecution.mockReset();
      runExperimentExecution.mockResolvedValue({
        experiments: [{ experiment_id: EXPERIMENT_A }],
        total_items: 1,
      });
      getExperimentById.mockReset();
      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.RUNNING,
        trace_count: 0,
      });
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        promptMap: {
          [PROMPT_A]: { id: PROMPT_A, name: "A", messages: [], configs: {} },
          [PROMPT_B]: { id: PROMPT_B, name: "B", messages: [], configs: {} },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    const runOneUntilItGivesUp = async () => {
      const rendered = renderActions("dataset-1");
      await act(async () => {
        await rendered.result.current.runSingle(PROMPT_A);
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(PAST_THE_WATCH_WINDOW);
      });
      return rendered;
    };

    it("should mark only that prompt as no longer followed", async () => {
      await runOneUntilItGivesUp();

      expect(usePlaygroundStore.getState().watchLapsed).toEqual(
        new Set([PROMPT_A]),
      );
    });

    // Two prompts run at different times give up at different times. The second to lapse must not
    // take the first one's place.
    it("should keep both when a second prompt gives up later", async () => {
      const { result } = await runOneUntilItGivesUp();
      expect(usePlaygroundStore.getState().watchLapsed).toEqual(
        new Set([PROMPT_A]),
      );

      await act(async () => {
        await result.current.runSingle(PROMPT_B);
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(PAST_THE_WATCH_WINDOW);
      });

      expect(usePlaygroundStore.getState().watchLapsed).toEqual(
        new Set([PROMPT_A, PROMPT_B]),
      );
    });

    // Otherwise the row keeps offering to refresh a run that is already being followed again.
    it("should follow it again once it is re-run", async () => {
      const { result } = await runOneUntilItGivesUp();
      expect(usePlaygroundStore.getState().watchLapsed).toEqual(
        new Set([PROMPT_A]),
      );

      await act(async () => {
        await result.current.runSingle(PROMPT_A);
      });

      expect(usePlaygroundStore.getState().watchLapsed.size).toBe(0);
    });
  });

  // The run outlives the window this tab is willing to watch it for. Settling it here would say the
  // run ended when it had not, which costs real money: Run comes back and the next click dispatches
  // the whole dataset a second time alongside the first.
  describe("a run that outlives the watch window", () => {
    // Past the five minutes the poll is willing to follow a run for.
    const PAST_THE_WATCH_WINDOW = 6 * 60 * 1000;

    beforeEach(() => {
      vi.useFakeTimers();
      getExperimentById.mockReset();
      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.RUNNING,
        trace_count: 0,
      });
      startBackendRun();
      usePlaygroundStore.setState({
        runTotalItemsByPromptId: { [PROMPT_A]: 10, [PROMPT_B]: 10 },
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    const watchUntilItGivesUp = async () => {
      let rendered!: ReturnType<typeof renderActions>;
      // Mounting picks the run back up on its own, which is a state update of its own.
      await act(async () => {
        rendered = renderActions("dataset-1");
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(PAST_THE_WATCH_WINDOW);
      });
      return rendered;
    };

    // Starting a run ends the one before it. A poll waiting on its own timer belongs to no abort
    // controller, so forgetting the controllers leaves it to wake into the new run and report the
    // old one's progress against it.
    it("should stop the previous run's poll when a new run starts", async () => {
      const { result } = await watchUntilItGivesUp();
      await act(async () => {
        result.current.checkAgain();
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS);
      });

      const readsOfTheOldRun = () =>
        getExperimentById.mock.calls.filter(
          (call) => call[1]?.experimentId === EXPERIMENT_A,
        ).length;
      const before = readsOfTheOldRun();
      expect(before).toBeGreaterThan(0);

      runExperimentExecution.mockResolvedValue({
        experiments: [{ experiment_id: "experiment-of-the-new-run" }],
        total_items: 1,
      });
      await act(async () => {
        await result.current.runAll();
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS * 3);
      });

      expect(readsOfTheOldRun()).toBe(before);
    });

    // Giving up is decided per poll, against the clock that poll started on, but it stops the whole
    // page watching. A poll started later is not past its own window yet, so without being told the
    // round is over it carries on polling behind a page that says it is not following anything —
    // and survives into the next round, leaving two polls on one run.
    it("should leave one poll on the run however often it is picked back up", async () => {
      const { result } = await watchUntilItGivesUp();

      // Twice, as an impatient second press would. Each one starts a poll of its own, and the
      // second has to end the first: aborting only ends the request in flight, so the chain behind
      // it would otherwise wake up on its own timer and follow the run alongside the new one.
      await act(async () => {
        result.current.checkAgain();
      });
      await act(async () => {
        result.current.checkAgain();
      });
      const afterPickingBackUp = getExperimentById.mock.calls.length;

      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS * 3);
      });

      // Three ticks of one poll over the two experiments. Two polls would read them twice over.
      expect(getExperimentById.mock.calls.length - afterPickingBackUp).toBe(6);
    });

    // The resume effect depends on a callback whose identity changes as the page settles — the
    // dataset type arriving flips isTestSuite, which rebuilds the poll. Picking the run up a second
    // time would leave two poll chains following it.
    it("should pick the run up once, however often its dependencies change", async () => {
      await act(async () => {
        renderActions("dataset-1");
      });
      const afterMount = getExperimentById.mock.calls.length;

      await act(async () => {
        usePlaygroundStore.setState({ datasetType: DATASET_TYPE.TEST_SUITE });
      });

      expect(getExperimentById.mock.calls.length).toBe(afterMount);
    });

    it("should keep the run running so Run cannot start a second one on top of it", async () => {
      await watchUntilItGivesUp();

      expect(usePlaygroundStore.getState().isRunningMap).toEqual({
        [PROMPT_A]: true,
        [PROMPT_B]: true,
      });
      expect(usePlaygroundStore.getState().isRunInFlight).toBe(true);
    });

    // Marked per prompt rather than as one flag: the same fact drives the progress bar for a run
    // followed as a whole and a single prompt's row when there is no bar.
    it("should say it stopped following every prompt it had been", async () => {
      await watchUntilItGivesUp();

      expect(usePlaygroundStore.getState().watchLapsed).toEqual(
        new Set([PROMPT_A, PROMPT_B]),
      );
    });

    it("should pick the run back up and settle it when asked to check again", async () => {
      const { result } = await watchUntilItGivesUp();
      expect(usePlaygroundStore.getState().watchLapsed.size).toBeGreaterThan(0);

      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.COMPLETED,
        trace_count: 10,
      });

      await act(async () => {
        result.current.checkAgain();
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(EXPERIMENT_POLL_INTERVAL_MS);
      });

      expect(usePlaygroundStore.getState().watchLapsed.size).toBe(0);
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({});
    });
  });
  // Reload: the persisted half of a run survives, the live half does not.
  describe("the page being reloaded mid-run", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      getExperimentById.mockReset();
      getExperimentById.mockResolvedValue({
        status: EXPERIMENT_STATUS.RUNNING,
        trace_count: 3,
      });
      // What partialize keeps, and what it drops.
      usePlaygroundStore.setState({
        promptIds: [PROMPT_A, PROMPT_B],
        experimentByPromptId: {
          [PROMPT_A]: EXPERIMENT_A,
          [PROMPT_B]: EXPERIMENT_B,
        },
        runTotalItemsByPromptId: { [PROMPT_A]: 10, [PROMPT_B]: 10 },
        isRunningMap: {},
        isRunInFlight: false,
        progressTotal: 0,
        progressCompleted: 0,
        progressPhase: null,
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should put the progress bar back", async () => {
      await act(async () => {
        renderActions("dataset-1");
      });

      expect(usePlaygroundStore.getState().progressTotal).toBe(20);
      expect(usePlaygroundStore.getState().progressPhase).toBe("running");
      expect(usePlaygroundStore.getState().isRunningMap).toEqual({
        [PROMPT_A]: true,
        [PROMPT_B]: true,
      });
    });
  });
});
