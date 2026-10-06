import { describe, it, expect, vi, beforeEach } from "vitest";
import React from "react";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import usePlaygroundStore from "@/store/PlaygroundStore";
import useActionButtonActions from "./useActionButtonActions";

const cancelExperimentRun = vi.fn();

vi.mock("@/api/playground/useCancelExperimentExecution", () => ({
  default: () => ({ mutate: cancelExperimentRun }),
}));

vi.mock("@/api/playground/useRunExperimentExecution", () => ({
  default: () => ({ mutateAsync: vi.fn() }),
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
  });
});
