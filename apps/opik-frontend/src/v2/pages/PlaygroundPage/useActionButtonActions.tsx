import { useCallback, useMemo } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { DATASET_TYPE, EVALUATION_METHOD } from "@/types/datasets";
import { processFilters, transformDataColumnFilters } from "@/lib/filters";
import { LogExperiment } from "@/types/playground";
import useRunExperimentExecution from "@/api/playground/useRunExperimentExecution";
import useBackendRunCancellation from "@/v2/pages/PlaygroundPage/useBackendRunCancellation";
import useClientSideRun from "@/v2/pages/PlaygroundPage/useClientSideRun";
import useRunWatcher from "@/v2/pages/PlaygroundPage/useRunWatcher";
import useRunResume from "@/v2/pages/PlaygroundPage/useRunResume";
import usePlaygroundStore, {
  getExperimentNameForPrompt,
  getExperimentNamesForPrompts,
  usePromptIds,
  usePromptMap,
  useResetOutputMap,
  useSelectedRuleIds,
  useSetCreatedExperiments,
  useSetRunTotalItemsForPrompts,
  useSetIsRunInFlight,
  useClearCreatedExperiments,
  useIsRunning,
  useSetAllRunning,
  useSettleRun,
  useSetPromptRunning,
  useSetProgress,
  useSetProgressPhase,
  useResetProgress,
  useForgetExperimentForPrompt,
  useDatasetType,
  useDatasetFilters,
  useSetExperimentByPromptId,
  useClearStopPending,
  useClearWatchLapsed,
} from "@/store/PlaygroundStore";

import { useToast } from "@/ui/use-toast";
import useOpenAiPipelineMode from "@/hooks/useOpenAiPipelineMode";

interface UseActionButtonActionsArguments {
  workspaceName: string;
  datasetName: string | null;
  datasetVersionId?: string;
  datasetId?: string;
  versionHash?: string;
  projectName?: string;
  projectId?: string | null;
}

// datasetType is persisted and can outlive the dataset it was set for (for
// example after switching projects). The backend run path silently returns
// without a dataset, so a stale type alone must not route runs there.
export const isTestSuiteRun = (
  datasetId: string | undefined,
  datasetType: DATASET_TYPE | null,
) => !!datasetId && datasetType === DATASET_TYPE.TEST_SUITE;

const useActionButtonActions = ({
  workspaceName,
  datasetName,
  datasetVersionId,
  datasetId,
  versionHash,
  projectName,
  projectId,
}: UseActionButtonActionsArguments) => {
  const queryClient = useQueryClient();

  const { toast } = useToast();

  const isRunning = useIsRunning();
  const setAllRunning = useSetAllRunning();
  const settleRun = useSettleRun();
  const setPromptRunning = useSetPromptRunning();
  const setCreatedExperiments = useSetCreatedExperiments();
  const setRunTotalItemsForPrompts = useSetRunTotalItemsForPrompts();
  const setIsRunInFlight = useSetIsRunInFlight();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const promptIds = usePromptIds();
  const promptMap = usePromptMap();
  const selectedRuleIds = useSelectedRuleIds();
  const datasetType = useDatasetType();
  const datasetFilters = useDatasetFilters();
  const setExperimentByPromptId = useSetExperimentByPromptId();
  const runExperimentExecution = useRunExperimentExecution();
  const openAiPipelineMode = useOpenAiPipelineMode(workspaceName);

  const isTestSuite = isTestSuiteRun(datasetId, datasetType);
  const evaluationMethod = isTestSuite
    ? EVALUATION_METHOD.TEST_SUITE
    : EVALUATION_METHOD.DATASET;

  // Keyed on the id alone: the name arrives from its own request, and falling back to the browser
  // while that is in flight would quietly give one run different semantics from the next.
  const isBackendRun = !!datasetId;

  const {
    refreshRunViews,
    releaseRunInFlight,
    cancelBackendRun,
    applyQueuedStop,
  } = useBackendRunCancellation(isBackendRun);

  const clearStopPending = useClearStopPending();
  const clearWatchLapsed = useClearWatchLapsed();

  const {
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
  } = useRunWatcher({
    workspaceName,
    isTestSuite,
    isBackendRun,
    datasetId,
    cancelBackendRun,
    releaseRunInFlight,
    refreshRunViews,
  });

  const { checkAgain } = useRunResume({
    datasetId,
    pollExperimentCompletion,
    stopPolling,
    refreshRunViews,
  });

  const reportDatasetNotReady = useCallback(() => {
    toast({
      title: "Error",
      description: "The dataset is still loading, try again in a moment",
      variant: "destructive",
    });
  }, [toast]);

  const datasetItemFilters = useMemo(
    () => processFilters(transformDataColumnFilters(datasetFilters)).filters,
    [datasetFilters],
  );

  const resetOutputMap = useResetOutputMap();
  const setProgress = useSetProgress();
  const setProgressPhase = useSetProgressPhase();
  const resetProgress = useResetProgress();
  const forgetExperimentForPrompt = useForgetExperimentForPrompt();

  const resetState = useCallback(() => {
    resetOutputMap();
    stopPolling();
    settleRun();
    clearCreatedExperiments();
    resetProgress();
  }, [
    resetOutputMap,
    stopPolling,
    clearCreatedExperiments,
    settleRun,
    resetProgress,
  ]);

  const storeExperiments = useCallback(
    (experiments: LogExperiment[]) => {
      setCreatedExperiments(experiments);
    },
    [setCreatedExperiments],
  );

  const { runAllViaFrontend, runSingleViaFrontend } = useClientSideRun({
    workspaceName,
    projectName,
    resetState,
    markWatchStarted,
    addAbortController,
    deleteAbortController,
    clearAbortControllers,
  });

  const runAllViaBackend = useCallback(async () => {
    if (!datasetId) return;
    if (!datasetName) return reportDatasetNotReady();

    resetState();
    markWatchStarted();
    setAllRunning(true);

    try {
      const prompts = promptIds.map((id) => promptMap[id]);
      const experimentNames = getExperimentNamesForPrompts(
        prompts.map((p) => p.id),
      );
      const response = await runExperimentExecution.mutateAsync({
        datasetName,
        datasetVersionId,
        datasetId,
        versionHash,
        prompts,
        projectName,
        projectId,
        experimentNames,
        openAiPipelineMode,
        selectedRuleIds,
        filters: datasetItemFilters,
      });

      // Build experiment-to-prompt mapping from BE response
      const experimentPromptMap: Record<string, string> = {};
      const experiments: LogExperiment[] = response.experiments.map((exp) => {
        const promptId = promptIds[exp.prompt_index];
        experimentPromptMap[promptId] = exp.experiment_id;
        return {
          id: exp.experiment_id,
          name: experimentNames[promptId],
          datasetName,
          datasetVersionId,
          evaluationMethod,
        };
      });

      storeExperiments(experiments);
      setExperimentByPromptId(experimentPromptMap);

      response.experiments.forEach((exp) => {
        const promptId = promptIds[exp.prompt_index];
        if (promptId) {
          setRunTotalItemsForPrompts([promptId], exp.total_items);
        }
      });

      if (applyQueuedStop(promptIds)) return;

      setIsRunInFlight(true);
      setProgressPhase("running");
      setProgress(0, response.total_items);

      queryClient.invalidateQueries({ queryKey: ["experiments"] });

      // Poll for completion instead of immediately finishing
      const experimentIds = response.experiments.map((e) => e.experiment_id);
      pollExperimentCompletion(experimentIds, response.total_items, datasetId, {
        announceExperiments: experiments,
      });
    } catch {
      settleRun();
      markWatchStarted();
    }
  }, [
    applyQueuedStop,
    markWatchStarted,
    datasetName,
    datasetId,
    datasetVersionId,
    versionHash,
    resetState,
    setAllRunning,
    settleRun,
    promptIds,
    promptMap,
    runExperimentExecution,
    openAiPipelineMode,
    storeExperiments,
    setExperimentByPromptId,
    setProgress,
    setProgressPhase,
    setRunTotalItemsForPrompts,
    setIsRunInFlight,
    queryClient,
    projectName,
    projectId,
    pollExperimentCompletion,
    reportDatasetNotReady,
    selectedRuleIds,
    datasetItemFilters,
    evaluationMethod,
  ]);

  const runAll = useCallback(async () => {
    if (isBackendRun) {
      return runAllViaBackend();
    }
    return runAllViaFrontend();
  }, [isBackendRun, runAllViaBackend, runAllViaFrontend]);

  const runSingleViaBackend = useCallback(
    async (promptId: string) => {
      if (!datasetId) return;
      if (!datasetName) return reportDatasetNotReady();
      const prompt = usePlaygroundStore.getState().promptMap[promptId];
      if (!prompt) return;

      setPromptRunning(promptId, true);
      expectScopedAnnouncement(promptId);
      // Until /execute answers, this prompt's previous experiment is the only id on hand, and a
      // stop pressed now would cancel that finished run instead of queueing for this one.
      const previousExperimentId =
        usePlaygroundStore.getState().experimentByPromptId?.[promptId];
      forgetExperimentForPrompt(promptId);
      // A fresh run is one this tab follows again, whatever happened to the last one.
      clearWatchLapsed([promptId]);

      // A launch that never started replaces nothing, so the last run stays on screen. Without
      // this the cell empties for good: the mapping it reads is persisted, so a reload keeps the
      // blank rather than restoring it.
      const keepShowingThePreviousRun = () => {
        if (!previousExperimentId) return;
        const current =
          usePlaygroundStore.getState().experimentByPromptId ?? {};
        setExperimentByPromptId({
          ...current,
          [promptId]: previousExperimentId,
        });
      };

      const experimentName = getExperimentNameForPrompt(prompt.id);

      try {
        const response = await runExperimentExecution.mutateAsync({
          datasetName,
          datasetVersionId,
          datasetId,
          versionHash,
          prompts: [prompt],
          projectName,
          projectId,
          experimentNames: { [prompt.id]: experimentName },
          openAiPipelineMode,
          selectedRuleIds,
          filters: datasetItemFilters,
        });

        const experiment = response.experiments[0];
        if (!experiment) {
          setPromptRunning(promptId, false);
          clearStopPending([promptId]);
          keepShowingThePreviousRun();
          return;
        }

        const existing =
          usePlaygroundStore.getState().experimentByPromptId ?? {};
        setExperimentByPromptId({
          ...existing,
          [promptId]: experiment.experiment_id,
        });
        setRunTotalItemsForPrompts([promptId], experiment.total_items);

        // Record the id before deciding whether to watch: a run stopped mid-launch still exists on
        // the server, and cancelling it needs the id the stop could not reach.
        if (applyQueuedStop([promptId])) return;
        if (!usePlaygroundStore.getState().isRunningMap[promptId]) {
          return;
        }

        setIsRunInFlight(true);

        queryClient.invalidateQueries({ queryKey: ["experiments"] });

        pollExperimentCompletion(
          [experiment.experiment_id],
          response.total_items,
          datasetId,
          {
            scopedPromptIds: [promptId],
            pollKeySuffix: `-${promptId}`,
            announceExperiments: [
              {
                id: experiment.experiment_id,
                name: experimentName,
                datasetName,
                datasetVersionId,
                evaluationMethod,
              },
            ],
          },
        );
      } catch {
        setPromptRunning(promptId, false);
        clearStopPending([promptId]);
        keepShowingThePreviousRun();
      }
    },
    [
      datasetName,
      datasetId,
      datasetVersionId,
      versionHash,
      projectName,
      projectId,
      runExperimentExecution,
      openAiPipelineMode,
      setPromptRunning,
      setExperimentByPromptId,
      setRunTotalItemsForPrompts,
      setIsRunInFlight,
      queryClient,
      pollExperimentCompletion,
      reportDatasetNotReady,
      selectedRuleIds,
      datasetItemFilters,
      evaluationMethod,
      applyQueuedStop,
      expectScopedAnnouncement,
      forgetExperimentForPrompt,
      clearStopPending,
      clearWatchLapsed,
    ],
  );

  const runSingle = useCallback(
    async (promptId: string) => {
      if (isBackendRun) {
        return runSingleViaBackend(promptId);
      }
      return runSingleViaFrontend(promptId);
    },
    [isBackendRun, runSingleViaBackend, runSingleViaFrontend],
  );

  // Picks a server-side run back up
  return {
    isRunning,
    runAll,
    runSingle,
    stopAll,
    stopWatching,
    stopSingle,
    checkAgain,
  };
};

export default useActionButtonActions;
