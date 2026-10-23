import { useCallback, useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { getExperimentById } from "@/api/datasets/useExperimentById";
import { isExperimentTerminal } from "@/lib/experiments";
import usePlaygroundStore, {
  useSetIsResumingRun,
  useSetIsRunInFlight,
  useSetProgress,
  useSetProgressPhase,
  useResetProgress,
  useSetPromptRunning,
  useClearAllWatchLapsed,
  useSettleRun,
} from "@/store/PlaygroundStore";
import { PollScope } from "@/v2/pages/PlaygroundPage/useRunWatcher";

interface UseRunResumeArguments {
  datasetId?: string;
  pollExperimentCompletion: (
    experimentIds: string[],
    totalItems: number,
    curDatasetId: string,
    scope?: PollScope,
  ) => void;
  stopPolling: () => void;
  refreshRunViews: () => void;
}

/**
 * Picking a run back up, which this page has to do because the run is not its own. A server-side
 * run carries on while the tab is closed, so arriving at the playground is a question of what is
 * already happening rather than a blank start.
 *
 * Done once per mount, and again on demand once the watch window has lapsed \u2014 statuses are read
 * fresh each time, so a run that ended while nobody was looking settles rather than reopening.
 */
export default function useRunResume({
  datasetId,
  pollExperimentCompletion,
  stopPolling,
  refreshRunViews,
}: UseRunResumeArguments) {
  const queryClient = useQueryClient();

  const settleRun = useSettleRun();
  const setPromptRunning = useSetPromptRunning();
  const setIsResumingRun = useSetIsResumingRun();
  const setIsRunInFlight = useSetIsRunInFlight();
  const setProgress = useSetProgress();
  const setProgressPhase = useSetProgressPhase();
  const resetProgress = useResetProgress();
  const clearAllWatchLapsed = useClearAllWatchLapsed();

  const pickUpRunningExperiments = useCallback(async () => {
    if (!datasetId) return;

    const {
      experimentByPromptId,
      runTotalItemsByPromptId,
      createdExperiments,
    } = usePlaygroundStore.getState();
    const entries = Object.entries(experimentByPromptId ?? {});
    if (entries.length === 0) return;

    // Set before the round-trip, not after: until it answers, the page knows a run exists but not
    // whether it is still going, and offering Run in that window starts a second one alongside it.
    setIsResumingRun(true);
    const experimentIds = entries.map(([, experimentId]) => experimentId);

    {
      try {
        // Through the cache the cells and the sidebar already share, so resuming does not read
        // the same experiments a second time.
        const experiments = await Promise.all(
          experimentIds.map((experimentId) =>
            queryClient.fetchQuery({
              queryKey: ["experiment", { experimentId }],
              queryFn: (context) =>
                getExperimentById(context, { experimentId }),
            }),
          ),
        );

        // The map keeps a prompt's experiment after its run ends, while runTotalItems describes only
        // the latest run. Picking the finished ones back up would offer Stop for a run that is over
        // and count its traces against a total that never included them.
        const resumable = entries
          .map(([promptId, experimentId], index) => ({
            promptId,
            experimentId,
            experiment: experiments[index],
          }))
          .filter(
            ({ experiment }) => !isExperimentTerminal(experiment?.status),
          );

        if (resumable.length === 0) {
          // All over while we were away. The per-prompt running state is not cleared by leaving and
          // survives navigating back, so without this the page returns offering Stop for a run that
          // has already finished.
          settleRun();
          resetProgress();
          return;
        }

        const resumableTotal = resumable.reduce(
          (sum, { promptId }) =>
            sum + (runTotalItemsByPromptId?.[promptId] ?? 0),
          0,
        );
        if (resumableTotal === 0) {
          settleRun();
          resetProgress();
          return;
        }

        resumable.forEach(({ promptId }) => setPromptRunning(promptId, true));
        setIsRunInFlight(true);
        setProgressPhase("running");
        // Seeded from the traces already logged, the same count the poll uses, so a run that is
        // nearly done reopens near the end rather than at zero until the first poll lands.
        const loggedTraces = resumable.reduce(
          (sum, { experiment }) => sum + (experiment?.trace_count ?? 0),
          0,
        );
        setProgress(Math.min(loggedTraces, resumableTotal), resumableTotal);
        pollExperimentCompletion(
          resumable.map(({ experimentId }) => experimentId),
          resumableTotal,
          datasetId,
          { announceExperiments: createdExperiments },
        );
      } catch {
        // A run we cannot read the status of is one we cannot resume; the cells still fill in on
        // their own, so leaving the page idle is better than a progress bar that never moves.
        // Settled rather than simply left alone: isRunInFlight is persisted, so giving up without
        // clearing it strands the sidebar watcher polling for a status that never arrives.
        settleRun();
        resetProgress();
      } finally {
        setIsResumingRun(false);
      }
    }
  }, [
    datasetId,
    queryClient,
    setIsResumingRun,
    settleRun,
    setPromptRunning,
    setProgress,
    setProgressPhase,
    resetProgress,
    pollExperimentCompletion,
    setIsRunInFlight,
  ]);

  const hasResumedRef = useRef(false);
  useEffect(() => {
    if (hasResumedRef.current || !datasetId) return;

    const { experimentByPromptId, isRunningMap } =
      usePlaygroundStore.getState();
    if (Object.keys(experimentByPromptId ?? {}).length === 0) {
      if (Object.keys(isRunningMap).length === 0) resetProgress();
      return;
    }

    hasResumedRef.current = true;
    pickUpRunningExperiments();
  }, [datasetId, pickUpRunningExperiments, resetProgress]);

  // Statuses are read fresh, anything still polling is dropped, so one poll covers every prompt
  // still running
  const checkAgain = useCallback(() => {
    stopPolling();
    clearAllWatchLapsed();
    refreshRunViews();
    pickUpRunningExperiments();
  }, [
    stopPolling,
    clearAllWatchLapsed,
    refreshRunViews,
    pickUpRunningExperiments,
  ]);

  return { checkAgain };
}
