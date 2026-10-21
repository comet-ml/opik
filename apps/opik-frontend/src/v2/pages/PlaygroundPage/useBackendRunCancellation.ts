import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { COMPARE_EXPERIMENTS_KEY } from "@/api/api";
import useCancelExperimentExecution from "@/api/playground/useCancelExperimentExecution";
import usePlaygroundStore, {
  getRunningPromptIds,
  useSetIsRunInFlight,
  useQueueStopPending,
  useClearAllStopPending,
} from "@/store/PlaygroundStore";

/**
 * Ending a server-side run, and the queue of stops that arrived before one could be named.
 *
 * A stop can be pressed at any point, including before `/execute` has answered with the experiment
 * ids — and `/execute` pages through the whole dataset before it answers, so that window grows
 * with the run rather than being a brief race. A stop with nothing to cancel is held here and
 * replayed the moment the ids land.
 *
 * Every entry is a prompt id, so a stop aimed at one prompt never reads as a stop of its siblings.
 */
export default function useBackendRunCancellation(isBackendRun: boolean) {
  const queryClient = useQueryClient();
  const setIsRunInFlight = useSetIsRunInFlight();
  const queueStopPending = useQueueStopPending();
  const clearAllStopPending = useClearAllStopPending();

  // Only `mutate` is stable; useMutation's object is new every render. stopAll ends up in an
  // unmount cleanup, which an unstable identity re-runs every render, stopping the run it began.
  const { mutate: cancelExperimentRun } = useCancelExperimentExecution();

  const refreshRunViews = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["experiment"] });
    queryClient.invalidateQueries({ queryKey: ["experiments"] });
    queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
  }, [queryClient]);

  // isRunInFlight is shared by every prompt, and the sidebar watches the run through it. An action
  // scoped to one prompt may only clear it once no sibling is left running.
  const releaseRunInFlight = useCallback(() => {
    if (getRunningPromptIds().length === 0) {
      setIsRunInFlight(false);
    }
  }, [setIsRunInFlight]);

  const cancelBackendRun = useCallback(
    (promptIds: string[]) => {
      if (!isBackendRun) return;

      const experimentByPromptId =
        usePlaygroundStore.getState().experimentByPromptId ?? {};

      // Split rather than all-or-nothing: a stop can cover prompts whose ids have arrived and
      // prompts whose /execute has not answered yet, and taking the first branch for the whole
      // set would drop the unnamed ones — their runs carry on with nothing left to cancel them.
      const named = promptIds.filter(
        (promptId) => experimentByPromptId[promptId],
      );
      const unnamed = promptIds.filter(
        (promptId) => !experimentByPromptId[promptId],
      );

      // The run exists on the server but this tab cannot name it. Remember those and replay them
      // when the ids arrive, keeping any stop already waiting so a second one does not drop it.
      queueStopPending(unnamed);

      if (named.length === 0) return;

      releaseRunInFlight();
      cancelExperimentRun({
        experimentIds: named.map((promptId) => experimentByPromptId[promptId]),
      });
      refreshRunViews();
    },
    [
      isBackendRun,
      cancelExperimentRun,
      releaseRunInFlight,
      refreshRunViews,
      queueStopPending,
    ],
  );

  /**
   * Applies a stop that was pressed before /execute answered. The mapping is in the store by the
   * time this runs, so the same cancel path that had nothing to work with now finds the ids.
   * Returns whether the run was stopped, so the caller knows not to start watching it.
   */
  const applyQueuedStop = useCallback(
    (launched: string[]) => {
      const queued = usePlaygroundStore.getState().stopPending;
      if (queued.size === 0) return false;

      clearAllStopPending();
      cancelBackendRun([...queued]);

      // Only a launch whose every prompt was stopped is over. A stop aimed at one of several
      // leaves its siblings running on the server, and they still need watching.
      return launched.every((promptId) => queued.has(promptId));
    },
    [cancelBackendRun, clearAllStopPending],
  );

  return {
    refreshRunViews,
    releaseRunInFlight,
    cancelBackendRun,
    applyQueuedStop,
  };
}
