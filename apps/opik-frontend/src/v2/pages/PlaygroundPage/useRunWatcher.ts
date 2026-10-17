import { useCallback, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import axios from "axios";

import { COMPARE_EXPERIMENTS_KEY } from "@/api/api";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import { getCompareExperimentsList } from "@/api/datasets/useCompareExperimentsList";
import {
  ASSERTION_POLL_INTERVAL_MS,
  COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
  EXPERIMENT_POLL_INTERVAL_MS,
} from "@/constants/experiments";
import { ExperimentsCompare } from "@/types/datasets";
import { LogExperiment } from "@/types/playground";
import { isExperimentTerminal } from "@/lib/experiments";
import { isItemScored } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useTestSuitePromptResults";
import usePlaygroundStore, {
  useSetProgress,
  useSetProgressPhase,
  useResetProgress,
  useSetPromptRunning,
  useSetWatchPaused,
  useSettleRun,
} from "@/store/PlaygroundStore";
import { useToast } from "@/ui/use-toast";
import useRunCompletionToast from "@/v2/pages/PlaygroundPage/useRunCompletionToast";

const MAX_POLL_DURATION_MS = 5 * 60 * 1000;
const MAX_POLL_DURATION_LABEL = "5 minutes";

const STILL_RUNNING_DESCRIPTION =
  `This run is taking longer than ${MAX_POLL_DURATION_LABEL}, so it is no longer being followed here. ` +
  "It continues on the server — refresh the progress bar to pick it back up.";

export interface PollScope {
  scopedPromptIds?: string[];
  pollKeySuffix?: string;
  announceExperiments?: LogExperiment[];
}

interface UseRunWatcherArguments {
  workspaceName: string;
  isTestSuite: boolean;
  isBackendRun: boolean;
  datasetId?: string;
  cancelBackendRun: (promptIds: string[]) => void;
  releaseRunInFlight: () => void;
  refreshRunViews: () => void;
}

/**
 * Following a run that is already going, and the three ways of stopping.
 *
 * A poll is a chain of timeouts rather than an interval, so aborting its in-flight request does
 * not end it — one waiting between ticks belongs to no controller. Moving the generation is what
 * ends a chain, which is why every stop goes through `stopPolling` rather than clearing the
 * controller map by hand.
 *
 * A *scoped* poll follows one prompt and deliberately ignores the stop flag, so that stopping one
 * prompt leaves its siblings being watched. The generation is therefore the only signal that
 * reaches every poll, and the reason the refs below stay private to this hook.
 */
export default function useRunWatcher({
  workspaceName,
  isTestSuite,
  isBackendRun,
  datasetId,
  cancelBackendRun,
  releaseRunInFlight,
  refreshRunViews,
}: UseRunWatcherArguments) {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const settleRun = useSettleRun();
  const setPromptRunning = useSetPromptRunning();
  const setProgress = useSetProgress();
  const setProgressPhase = useSetProgressPhase();
  const resetProgress = useResetProgress();
  const setWatchPaused = useSetWatchPaused();
  const announceRunComplete = useRunCompletionToast(datasetId);

  const isToStopRef = useRef(false);
  const abortControllersRef = useRef(
    new Map<string, { controller: AbortController; promptId: string }>(),
  );
  const pollGenerationRef = useRef(0);
  const scopedAnnounceRef = useRef(new Set<string>());

  const stopPolling = useCallback(() => {
    abortControllersRef.current.forEach(({ controller }) => controller.abort());
    abortControllersRef.current.clear();
    pollGenerationRef.current += 1;
  }, []);

  /** A run that is starting is one this tab is willing to follow again. */
  const markWatchStarted = useCallback(() => {
    isToStopRef.current = false;
  }, []);

  /** Announce this prompt's own completion when its scoped poll settles. */
  const expectScopedAnnouncement = useCallback((promptId: string) => {
    scopedAnnounceRef.current.add(promptId);
  }, []);

  const addAbortController = useCallback(
    (key: string, value: AbortController, promptId: string) => {
      abortControllersRef.current.set(key, { controller: value, promptId });
    },
    [],
  );

  const deleteAbortController = useCallback(
    (key: string) => abortControllersRef.current.delete(key),
    [],
  );

  const clearAbortControllers = useCallback(
    () => abortControllersRef.current.clear(),
    [],
  );

  const pauseWatching = useCallback(
    (description: string) => {
      stopPolling();
      setWatchPaused(true);
      refreshRunViews();
      toast({ title: "Still running", description });
    },
    [stopPolling, setWatchPaused, refreshRunViews, toast],
  );

  const finishPollScope = useCallback(
    (scope?: PollScope) => {
      if (scope?.scopedPromptIds) {
        scope.scopedPromptIds.forEach((id) => setPromptRunning(id, false));
        releaseRunInFlight();
      } else {
        settleRun();
        isToStopRef.current = false;
      }
    },
    [settleRun, setPromptRunning, releaseRunInFlight],
  );

  const handlePollError = useCallback(
    (
      error: unknown,
      scope: PollScope | undefined,
      description: string,
      onUnscopedCleanup?: () => void,
    ) => {
      // A cancelled poll means this tab stopped watching, not that the run stopped. Both paths
      // that abort it already settle the run; doing it here too ends a live one.
      if (axios.isCancel(error)) {
        return;
      }

      const isScoped = !!scope?.scopedPromptIds;
      finishPollScope(scope);
      if (!isScoped) {
        onUnscopedCleanup?.();
      }

      toast({
        title: "Error",
        description,
        variant: "destructive",
      });
    },
    [finishPollScope, toast],
  );

  // TODO: OPIK-5724 — for datasets >20k items, replace with a dedicated BE count endpoint
  const pollAssertionEvaluation = useCallback(
    async (
      experimentIds: string[],
      curDatasetId: string,
      scope?: PollScope,
    ) => {
      const startTime = Date.now();
      const pollKey = `poll-assertion${scope?.pollKeySuffix ?? ""}`;
      const isScoped = !!scope?.scopedPromptIds;
      const generation = pollGenerationRef.current;

      const poll = async () => {
        if (generation !== pollGenerationRef.current) return;
        if (!isScoped && isToStopRef.current) return;

        if (Date.now() - startTime > MAX_POLL_DURATION_MS) {
          pauseWatching(STILL_RUNNING_DESCRIPTION);
          return;
        }

        try {
          const controller = new AbortController();
          abortControllersRef.current.set(pollKey, {
            controller,
            promptId: scope?.scopedPromptIds?.[0] ?? "",
          });

          const [data, ...experimentResults] = await Promise.all([
            getCompareExperimentsList(
              { signal: controller.signal },
              {
                workspaceName,
                datasetId: curDatasetId,
                experimentsIds: experimentIds,
                truncate: true,
                size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
                page: 1,
              },
            ),
            ...experimentIds.map((id) =>
              getExperimentById(
                { signal: controller.signal },
                { experimentId: id },
              ),
            ),
          ]);

          const experimentsFinished = experimentResults.every((exp) =>
            isExperimentTerminal(exp?.status),
          );

          const rows: ExperimentsCompare[] = data?.content ?? [];

          let scoredItems = 0;
          let totalExperimentItems = 0;

          for (const row of rows) {
            const experimentItems = row.experiment_items ?? [];
            const noEvaluators = (row.evaluators?.length ?? 0) === 0;

            for (const ei of experimentItems) {
              totalExperimentItems++;
              if (noEvaluators || isItemScored(ei)) {
                scoredItems++;
              }
            }
          }

          if (!isScoped && totalExperimentItems > 0) {
            setProgress(scoredItems, totalExperimentItems);
          }

          if (experimentsFinished) {
            if (!isScoped) {
              setProgress(totalExperimentItems, totalExperimentItems);
              setTimeout(() => resetProgress(), 3000);
            }
            finishPollScope(scope);
            const scopedId = scope?.scopedPromptIds?.[0];
            if (!scopedId || scopedAnnounceRef.current.delete(scopedId)) {
              announceRunComplete(scope?.announceExperiments ?? []);
            }
            refreshRunViews();
            return;
          }

          queryClient.invalidateQueries({
            queryKey: [COMPARE_EXPERIMENTS_KEY],
          });
          setTimeout(poll, ASSERTION_POLL_INTERVAL_MS);
        } catch (error) {
          handlePollError(
            error,
            scope,
            "Failed to poll assertion evaluation status",
            resetProgress,
          );
        }
      };

      poll();
    },
    [
      workspaceName,
      setProgress,
      resetProgress,
      pauseWatching,
      refreshRunViews,
      announceRunComplete,
      finishPollScope,
      queryClient,
      handlePollError,
    ],
  );

  const pollExperimentCompletion = useCallback(
    async (
      experimentIds: string[],
      totalItems: number,
      curDatasetId: string,
      scope?: PollScope,
    ) => {
      const startTime = Date.now();
      const pollKey = `poll-experiment${scope?.pollKeySuffix ?? ""}`;
      const isScoped = !!scope?.scopedPromptIds;
      const generation = pollGenerationRef.current;

      const poll = async () => {
        if (generation !== pollGenerationRef.current) return;
        if (!isScoped && isToStopRef.current) return;

        if (Date.now() - startTime > MAX_POLL_DURATION_MS) {
          pauseWatching(STILL_RUNNING_DESCRIPTION);
          return;
        }

        try {
          const controller = new AbortController();
          abortControllersRef.current.set(pollKey, {
            controller,
            promptId: scope?.scopedPromptIds?.[0] ?? "",
          });
          const results = await Promise.all(
            experimentIds.map((id) =>
              getExperimentById(
                { signal: controller.signal },
                {
                  experimentId: id,
                },
              ),
            ),
          );

          const totalTraces = results.reduce(
            (sum, exp) => sum + (exp?.trace_count ?? 0),
            0,
          );
          if (!isScoped) {
            setProgress(Math.min(totalTraces, totalItems), totalItems);
          }

          const allExperimentsFinished = results.every((exp) =>
            isExperimentTerminal(exp?.status),
          );

          if (allExperimentsFinished) {
            // Assertions already done — skip Step 2, finish immediately
            if (!isScoped) {
              setProgress(totalItems, totalItems);
              setTimeout(() => resetProgress(), 3000);
            }
            finishPollScope(scope);
            const scopedId = scope?.scopedPromptIds?.[0];
            if (!scopedId || scopedAnnounceRef.current.delete(scopedId)) {
              announceRunComplete(scope?.announceExperiments ?? []);
            }
            refreshRunViews();
            return;
          }

          // A regular dataset has no assertions to wait for
          const allTracesCollected = totalTraces >= totalItems;
          if (allTracesCollected && isTestSuite) {
            if (!isScoped) {
              setProgress(totalItems, totalItems);
              setProgressPhase("evaluating");
              setProgress(0, totalItems);
            }
            queryClient.invalidateQueries({ queryKey: ["experiments"] });
            pollAssertionEvaluation(experimentIds, curDatasetId, scope);
            return;
          }

          queryClient.invalidateQueries({ queryKey: ["experiments"] });
          setTimeout(poll, EXPERIMENT_POLL_INTERVAL_MS);
        } catch (error) {
          handlePollError(
            error,
            scope,
            "Failed to poll experiment completion status",
          );
        }
      };

      setTimeout(poll, EXPERIMENT_POLL_INTERVAL_MS);
    },
    [
      setProgress,
      setProgressPhase,
      resetProgress,
      finishPollScope,
      refreshRunViews,
      announceRunComplete,
      pauseWatching,
      queryClient,
      pollAssertionEvaluation,
      handlePollError,
      isTestSuite,
    ],
  );

  /**
   * Leaving the page, as opposed to stopping the run. A server-side run outlives it, so cancelling
   * here would end the run, and clearing the in-flight flag would stop the sidebar watching for it.
   */
  const stopWatching = useCallback(() => {
    // The poll is a timeout chain and outlives the page; left running it settles the run here.
    // A scoped poll ignores isToStopRef by design, so moving the generation is what reaches it.
    stopPolling();
    isToStopRef.current = true;

    if (!isBackendRun) {
      settleRun();
    }
  }, [stopPolling, settleRun, isBackendRun]);

  const stopAll = useCallback(() => {
    // Read before settling, which empties both: these are the prompts the stop covers, and a
    // stop that has to wait for ids needs to name them. A prompt stopped on its own is already
    // out of the running map with its stop still queued, so carry that across or it is lost.
    const { isRunningMap, stopPending } = usePlaygroundStore.getState();
    const running = Object.keys(isRunningMap).filter((id) => isRunningMap[id]);
    const covered = [...new Set([...running, ...(stopPending ?? [])])];

    scopedAnnounceRef.current.clear();
    settleRun();
    isToStopRef.current = true;
    stopPolling();
    cancelBackendRun(covered);
  }, [settleRun, stopPolling, cancelBackendRun]);

  const stopSingle = useCallback(
    (promptId: string) => {
      scopedAnnounceRef.current.delete(promptId);
      setPromptRunning(promptId, false);
      for (const [key, entry] of abortControllersRef.current.entries()) {
        if (entry.promptId === promptId) {
          entry.controller.abort();
          abortControllersRef.current.delete(key);
        }
      }
      cancelBackendRun([promptId]);
    },
    [setPromptRunning, cancelBackendRun],
  );

  return {
    pollExperimentCompletion,
    stopPolling,
    stopWatching,
    stopAll,
    stopSingle,
    markWatchStarted,
    expectScopedAnnouncement,
    addAbortController,
    deleteAbortController,
    clearAbortControllers,
  };
}
