import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { COMPARE_EXPERIMENTS_KEY } from "@/api/api";
import useCancelExperimentExecution from "@/api/playground/useCancelExperimentExecution";
import usePlaygroundStore, {
  getRunningPromptIds,
  useSetIsRunInFlight,
  useSetPromptRunning,
  useResetProgress,
  useQueueStopAwaitingRun,
  useClearAllStopsAwaitingRun,
  useMarkCancelInFlight,
  useClearCancelInFlight,
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
  const setPromptRunning = useSetPromptRunning();
  const resetProgress = useResetProgress();
  const queueStopAwaitingRun = useQueueStopAwaitingRun();
  const clearAllStopsAwaitingRun = useClearAllStopsAwaitingRun();
  const markCancelInFlight = useMarkCancelInFlight();
  const clearCancelInFlight = useClearCancelInFlight();

  // Destructured because useMutation's object is new every render. mutateAsync, not mutate: a
  // per-call onSettled only fires for the latest call, so overlapping stops lost one's settle.
  const { mutateAsync: cancelExperimentRun } = useCancelExperimentExecution();

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

      const { experimentByPromptId = {}, cancelsInFlight } =
        usePlaygroundStore.getState();

      // A prompt whose cancel is already out needs no second one
      const toCancel = promptIds.filter(
        (promptId) => !cancelsInFlight.has(promptId),
      );

      // Split rather than all-or-nothing: a stop can cover prompts whose ids have arrived and
      // prompts whose /execute has not answered yet, and taking the first branch for the whole
      // set would drop the unnamed ones — their runs carry on with nothing left to cancel them.
      const named = toCancel.filter(
        (promptId) => experimentByPromptId[promptId],
      );
      const unnamed = toCancel.filter(
        (promptId) => !experimentByPromptId[promptId],
      );

      // The run exists on the server but this tab cannot name it. Remember those and replay them
      // when the ids arrive, keeping any stop already waiting so a second one does not drop it.
      queueStopAwaitingRun(unnamed);

      if (named.length === 0) return;

      // Settled when the server answers, not when the button is pressed. The call writes the
      // cancelled status and purges the queue. Items a consumer already holds still finish.
      // Marked stopping meanwhile, so no button offers a Stop whose cancel is already on its way.
      // Paired with their ids: the settle lands later, and by then a prompt may hold a run this
      // cancel never covered. Settling that one would stop a page watching a run still going.
      const cancelled = named.map((promptId) => ({
        promptId,
        experimentId: experimentByPromptId[promptId],
      }));

      markCancelInFlight(named);
      cancelExperimentRun({
        experimentIds: cancelled.map(({ experimentId }) => experimentId),
      })
        .catch(() => {})
        .finally(() => {
          const current =
            usePlaygroundStore.getState().experimentByPromptId ?? {};
          cancelled
            .filter(
              ({ promptId, experimentId }) =>
                current[promptId] === experimentId,
            )
            .forEach(({ promptId }) => setPromptRunning(promptId, false));
          // Unconditional: the mark is this call's, so a prompt that moved on would keep it for
          // ever. A newer cancel sets its own, and clears it from its own settle.
          clearCancelInFlight(named);
          if (getRunningPromptIds().length === 0) resetProgress();
          releaseRunInFlight();
          refreshRunViews();
        });
    },
    [
      isBackendRun,
      cancelExperimentRun,
      releaseRunInFlight,
      refreshRunViews,
      queueStopAwaitingRun,
      markCancelInFlight,
      clearCancelInFlight,
      setPromptRunning,
      resetProgress,
    ],
  );

  /**
   * Applies a stop that was pressed before /execute answered. The mapping is in the store by the
   * time this runs, so the same cancel path that had nothing to work with now finds the ids.
   * Returns whether the run was stopped, so the caller knows not to start watching it.
   */
  const applyQueuedStop = useCallback(
    (launched: string[]) => {
      const queued = usePlaygroundStore.getState().stopsAwaitingRun;
      if (queued.size === 0) return false;

      clearAllStopsAwaitingRun();
      cancelBackendRun([...queued]);

      // Only a launch whose every prompt was stopped is over. A stop aimed at one of several
      // leaves its siblings running on the server, and they still need watching.
      return launched.every((promptId) => queued.has(promptId));
    },
    [cancelBackendRun, clearAllStopsAwaitingRun],
  );

  return {
    refreshRunViews,
    releaseRunInFlight,
    cancelBackendRun,
    applyQueuedStop,
  };
}
