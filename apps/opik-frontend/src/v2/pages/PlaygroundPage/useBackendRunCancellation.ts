import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { COMPARE_EXPERIMENTS_KEY } from "@/api/api";
import useCancelExperimentExecution from "@/api/playground/useCancelExperimentExecution";
import usePlaygroundStore, {
  useSetIsRunInFlight,
  useSetStopPending,
} from "@/store/PlaygroundStore";

/**
 * Ending a server-side run, and the queue of stops that arrived before one could be named.
 *
 * A stop can be pressed at any point, including before `/execute` has answered with the experiment
 * ids — and the publish pages the whole dataset before it answers, so that window grows with the
 * run rather than being a brief race. A stop with nothing to cancel is held here and replayed the
 * moment the ids land.
 *
 * Every entry is a prompt id, so a stop aimed at one prompt never reads as a stop of its siblings.
 */
export default function useBackendRunCancellation(isBackendRun: boolean) {
  const queryClient = useQueryClient();
  const setIsRunInFlight = useSetIsRunInFlight();
  const setStopPending = useSetStopPending();

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
    const { isRunningMap } = usePlaygroundStore.getState();
    if (!Object.values(isRunningMap).some(Boolean)) {
      setIsRunInFlight(false);
    }
  }, [setIsRunInFlight]);

  const cancelBackendRun = useCallback(
    (promptIds: string[]) => {
      if (!isBackendRun) return;

      const experimentByPromptId =
        usePlaygroundStore.getState().experimentByPromptId ?? {};
      const experimentIds = promptIds
        .map((promptId) => experimentByPromptId[promptId])
        .filter(Boolean);

      // Nothing to cancel by id yet: /execute has not answered, so the run exists on the server
      // but this tab cannot name it. Remember the stop and replay it when the ids arrive, keeping
      // any stop already waiting so a second one does not drop the first.
      if (experimentIds.length === 0) {
        const waiting = usePlaygroundStore.getState().stopPending ?? [];
        setStopPending([...new Set([...waiting, ...promptIds])]);
        return;
      }

      releaseRunInFlight();
      cancelExperimentRun({ experimentIds });
      refreshRunViews();
    },
    [
      isBackendRun,
      cancelExperimentRun,
      releaseRunInFlight,
      refreshRunViews,
      setStopPending,
    ],
  );

  /**
   * The launch failed, so a stop waiting on its ids has nothing left to cancel. Dropping only this
   * prompt leaves a stop queued against a sibling launch that is still open.
   */
  const dropQueuedStop = useCallback(
    (promptId: string) => {
      const waiting = usePlaygroundStore.getState().stopPending;
      if (!waiting) return;

      setStopPending(waiting.filter((id) => id !== promptId));
    },
    [setStopPending],
  );

  /**
   * Applies a stop that was pressed before /execute answered. The mapping is in the store by the
   * time this runs, so the same cancel path that had nothing to work with now finds the ids.
   * Returns whether the run was stopped, so the caller knows not to start watching it.
   */
  const applyQueuedStop = useCallback(
    (launched: string[]) => {
      const queued = usePlaygroundStore.getState().stopPending;
      if (!queued) return false;

      setStopPending(null);
      cancelBackendRun(queued);

      // Only a launch whose every prompt was stopped is over. A stop aimed at one of several
      // leaves its siblings running on the server, and they still need watching.
      return launched.every((promptId) => queued.includes(promptId));
    },
    [cancelBackendRun, setStopPending],
  );

  return {
    refreshRunViews,
    releaseRunInFlight,
    cancelBackendRun,
    dropQueuedStop,
    applyQueuedStop,
  };
}
