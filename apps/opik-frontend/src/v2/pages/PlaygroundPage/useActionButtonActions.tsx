import { useCallback, useMemo, useRef } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";
import { getExperimentById } from "@/api/datasets/useExperimentById";

import { COMPARE_EXPERIMENTS_KEY, PROJECTS_KEY } from "@/api/api";
import { getCompareExperimentsList } from "@/api/datasets/useCompareExperimentsList";
import {
  ASSERTION_POLL_INTERVAL_MS,
  COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
  EXPERIMENT_POLL_INTERVAL_MS,
} from "@/constants/experiments";
import {
  DatasetItem,
  DATASET_TYPE,
  EVALUATION_METHOD,
  EXPERIMENT_STATUS,
  ExperimentsCompare,
} from "@/types/datasets";
import { LogExperiment } from "@/types/playground";
import useRunExperimentExecution from "@/api/playground/useRunExperimentExecution";
import usePlaygroundStore, {
  usePromptIds,
  usePromptMap,
  useResetOutputMap,
  useSelectedRuleIds,
  useSetCreatedExperiments,
  useClearCreatedExperiments,
  useIsRunning,
  useSetAllRunning,
  useClearRunningMap,
  useSetPromptRunning,
  useSetProgress,
  useSetProgressPhase,
  useResetProgress,
  useUpdateOutputTraceId,
  useDatasetType,
  useSetExperimentByPromptId,
} from "@/store/PlaygroundStore";

import { useToast } from "@/ui/use-toast";
import createLogPlaygroundProcessor, {
  LogProcessorArgs,
  TraceMapping,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptDatasetItemCombination, {
  DatasetItemPromptCombination,
} from "@/v2/pages/PlaygroundPage/usePromptDatasetItemCombination";

const DEFAULT_MAX_CONCURRENT_REQUESTS = 5;
const MAX_POLL_DURATION_MS = 5 * 60 * 1000; // 5 minutes

interface UseActionButtonActionsArguments {
  datasetItems: DatasetItem[];
  workspaceName: string;
  datasetName: string | null;
  datasetVersionId?: string;
  datasetId?: string;
  versionHash?: string;
  projectName?: string;
}

const useActionButtonActions = ({
  datasetItems,
  workspaceName,
  datasetName,
  datasetVersionId,
  datasetId,
  versionHash,
  projectName,
}: UseActionButtonActionsArguments) => {
  const queryClient = useQueryClient();

  const { toast } = useToast();

  const isRunning = useIsRunning();
  const setAllRunning = useSetAllRunning();
  const clearRunningMap = useClearRunningMap();
  const setPromptRunning = useSetPromptRunning();
  const isToStopRef = useRef(false);
  const setCreatedExperiments = useSetCreatedExperiments();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const promptIds = usePromptIds();
  const promptMap = usePromptMap();
  const selectedRuleIds = useSelectedRuleIds();
  const datasetType = useDatasetType();
  const setExperimentByPromptId = useSetExperimentByPromptId();
  const abortControllersRef = useRef(new Map<string, AbortController>());
  const runExperimentExecution = useRunExperimentExecution();

  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;

  // Get the minimum maxConcurrentRequests from all prompts
  const maxConcurrentRequests = useMemo(() => {
    const prompts = Object.values(promptMap);
    if (prompts.length === 0) return DEFAULT_MAX_CONCURRENT_REQUESTS;

    const concurrencyValues = prompts
      .map((p) => p.configs.maxConcurrentRequests)
      .filter((val) => val !== undefined && val !== null) as number[];

    if (concurrencyValues.length === 0) return DEFAULT_MAX_CONCURRENT_REQUESTS;

    // Use the minimum value across all prompts (most conservative)
    return Math.min(...concurrencyValues);
  }, [promptMap]);

  // Get the maximum throttling from all prompts (most conservative)
  const throttlingSeconds = useMemo(() => {
    const prompts = Object.values(promptMap);
    if (prompts.length === 0) return 0;

    const throttlingValues = prompts
      .map((p) => p.configs.throttling)
      .filter((val) => val !== undefined && val !== null) as number[];

    if (throttlingValues.length === 0) return 0;

    // Use the maximum value across all prompts (most conservative)
    return Math.max(...throttlingValues);
  }, [promptMap]);

  const resetOutputMap = useResetOutputMap();
  const updateOutputTraceId = useUpdateOutputTraceId();
  const setProgress = useSetProgress();
  const setProgressPhase = useSetProgressPhase();
  const resetProgress = useResetProgress();

  const resetState = useCallback(() => {
    resetOutputMap();
    abortControllersRef.current.clear();
    clearRunningMap();
    clearCreatedExperiments();
    resetProgress();
  }, [resetOutputMap, clearCreatedExperiments, clearRunningMap, resetProgress]);

  const stopAll = useCallback(() => {
    clearRunningMap();
    isToStopRef.current = true;
    abortControllersRef.current.forEach((controller) => controller.abort());
    abortControllersRef.current.clear();
  }, [clearRunningMap]);

  const stopSingle = useCallback(
    (promptId: string) => {
      setPromptRunning(promptId, false);
      const controller = abortControllersRef.current.get(promptId);
      if (controller) {
        controller.abort();
        abortControllersRef.current.delete(promptId);
      }
    },
    [setPromptRunning],
  );

  const storeExperiments = useCallback(
    (experiments: LogExperiment[]) => {
      setCreatedExperiments(experiments);
    },
    [setCreatedExperiments],
  );

  const logProcessorHandlers: LogProcessorArgs = useMemo(() => {
    return {
      onAddExperimentRegistry: (experiments, experimentPromptMap) => {
        // Only store experiments when all have been created
        if (experiments.length === promptIds.length) {
          storeExperiments(experiments);
          setExperimentByPromptId(experimentPromptMap);
          queryClient.invalidateQueries({
            queryKey: ["experiments"],
          });
        }
      },
      onError: (e) => {
        toast({
          title: "Error",
          variant: "destructive",
          description: e.message,
        });
      },
      onCreateTraces: (traces, mappings: TraceMapping[]) => {
        // Store trace IDs in the output map
        mappings.forEach((mapping) => {
          updateOutputTraceId(
            mapping.promptId,
            mapping.datasetItemId || "",
            mapping.traceId,
          );
        });

        queryClient.invalidateQueries({
          queryKey: [PROJECTS_KEY],
        });
      },
      onExperimentItemsComplete: () => {
        // Invalidate experiments to refresh the experiments list
        queryClient.invalidateQueries({
          queryKey: ["experiments"],
        });
      },
    };
  }, [
    queryClient,
    promptIds.length,
    storeExperiments,
    setExperimentByPromptId,
    toast,
    updateOutputTraceId,
  ]);

  const addAbortController = useCallback(
    (key: string, value: AbortController) => {
      abortControllersRef.current.set(key, value);
    },
    [],
  );

  const deleteAbortController = useCallback(
    (key: string) => abortControllersRef.current.delete(key),
    [],
  );

  const { createCombinations, processCombination } =
    usePromptDatasetItemCombination({
      workspaceName,
      isToStopRef,
      datasetItems,
      datasetName,
      datasetVersionId,
      selectedRuleIds,
      addAbortController,
      deleteAbortController,
      throttlingSeconds,
    });

  const handlePollTimeout = useCallback(
    (description: string) => {
      clearRunningMap();
      isToStopRef.current = false;
      resetProgress();
      queryClient.invalidateQueries({ queryKey: ["experiments"] });
      queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
      toast({ title: "Timeout", description, variant: "destructive" });
    },
    [clearRunningMap, resetProgress, queryClient, toast],
  );

  // TODO: OPIK-5724 — for datasets >20k items, replace with a dedicated BE count endpoint
  const pollAssertionEvaluation = useCallback(
    async (experimentIds: string[], curDatasetId: string) => {
      const startTime = Date.now();
      const poll = async () => {
        if (isToStopRef.current) return;

        if (Date.now() - startTime > MAX_POLL_DURATION_MS) {
          handlePollTimeout(
            "Assertion evaluation polling timed out after 5 minutes",
          );
          return;
        }

        try {
          const controller = new AbortController();
          abortControllersRef.current.set("poll-assertion", controller);
          const data = await getCompareExperimentsList(
            { signal: controller.signal },
            {
              workspaceName,
              datasetId: curDatasetId,
              experimentsIds: experimentIds,
              truncate: true,
              size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
              page: 1,
            },
          );

          const rows: ExperimentsCompare[] = data?.content ?? [];
          const totalItems = (data?.total ?? 0) * experimentIds.length;

          let scoredItems = 0;

          for (const row of rows) {
            const experimentItems = row.experiment_items ?? [];
            for (const ei of experimentItems) {
              if (ei.status != null) {
                scoredItems++;
              }
            }
          }

          if (totalItems > 0) {
            setProgress(scoredItems, totalItems);
          }

          if (totalItems > 0 && scoredItems >= totalItems) {
            setProgress(totalItems, totalItems);
            clearRunningMap();
            isToStopRef.current = false;
            queryClient.invalidateQueries({ queryKey: ["experiments"] });
            queryClient.invalidateQueries({
              queryKey: [COMPARE_EXPERIMENTS_KEY],
            });
            setTimeout(() => resetProgress(), 3000);
            return;
          }

          queryClient.invalidateQueries({
            queryKey: [COMPARE_EXPERIMENTS_KEY],
          });
          setTimeout(poll, ASSERTION_POLL_INTERVAL_MS);
        } catch {
          clearRunningMap();
          isToStopRef.current = false;
          resetProgress();
          toast({
            title: "Error",
            description: "Failed to poll assertion evaluation status",
            variant: "destructive",
          });
        }
      };

      setTimeout(poll, ASSERTION_POLL_INTERVAL_MS);
    },
    [
      workspaceName,
      setProgress,
      resetProgress,
      clearRunningMap,
      handlePollTimeout,
      queryClient,
      toast,
    ],
  );

  const pollExperimentCompletion = useCallback(
    async (
      experimentIds: string[],
      totalItems: number,
      curDatasetId: string,
    ) => {
      const startTime = Date.now();
      const poll = async () => {
        if (isToStopRef.current) return;

        if (Date.now() - startTime > MAX_POLL_DURATION_MS) {
          handlePollTimeout(
            "Experiment completion polling timed out after 5 minutes",
          );
          return;
        }

        try {
          const controller = new AbortController();
          abortControllersRef.current.set("poll-experiment", controller);
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
          setProgress(Math.min(totalTraces, totalItems), totalItems);

          const allDone = results.every(
            (exp) =>
              exp?.status === EXPERIMENT_STATUS.COMPLETED ||
              exp?.status === EXPERIMENT_STATUS.CANCELLED,
          );

          if (allDone) {
            setProgress(totalItems, totalItems);
            queryClient.invalidateQueries({ queryKey: ["experiments"] });

            // Transition to evaluation phase
            setProgressPhase("evaluating");
            setProgress(0, totalItems);
            pollAssertionEvaluation(experimentIds, curDatasetId);
            return;
          }

          queryClient.invalidateQueries({ queryKey: ["experiments"] });
          setTimeout(poll, EXPERIMENT_POLL_INTERVAL_MS);
        } catch {
          clearRunningMap();
          isToStopRef.current = false;
          toast({
            title: "Error",
            description: "Failed to poll experiment completion status",
            variant: "destructive",
          });
        }
      };

      setTimeout(poll, EXPERIMENT_POLL_INTERVAL_MS);
    },
    [
      setProgress,
      setProgressPhase,
      clearRunningMap,
      handlePollTimeout,
      queryClient,
      pollAssertionEvaluation,
      toast,
    ],
  );

  const runAllViaBackend = useCallback(async () => {
    if (!datasetName || !datasetId) return;

    resetState();
    isToStopRef.current = false;
    setAllRunning(true);

    try {
      const prompts = promptIds.map((id) => promptMap[id]);
      const response = await runExperimentExecution.mutateAsync({
        datasetName,
        datasetVersionId,
        datasetId,
        versionHash,
        prompts,
        projectName,
      });

      // Build experiment-to-prompt mapping from BE response
      const experimentPromptMap: Record<string, string> = {};
      const experiments: LogExperiment[] = response.experiments.map((exp) => {
        const promptId = promptIds[exp.prompt_index];
        experimentPromptMap[promptId] = exp.experiment_id;
        return {
          id: exp.experiment_id,
          datasetName,
          datasetVersionId,
          evaluationMethod: EVALUATION_METHOD.TEST_SUITE,
        };
      });

      storeExperiments(experiments);
      setExperimentByPromptId(experimentPromptMap);
      setProgressPhase("running");
      setProgress(0, response.total_items);

      queryClient.invalidateQueries({ queryKey: ["experiments"] });

      // Poll for completion instead of immediately finishing
      const experimentIds = response.experiments.map((e) => e.experiment_id);
      pollExperimentCompletion(experimentIds, response.total_items, datasetId);
    } catch {
      clearRunningMap();
      isToStopRef.current = false;
    }
  }, [
    datasetName,
    datasetId,
    datasetVersionId,
    versionHash,
    resetState,
    setAllRunning,
    clearRunningMap,
    promptIds,
    promptMap,
    runExperimentExecution,
    storeExperiments,
    setExperimentByPromptId,
    setProgress,
    setProgressPhase,
    queryClient,
    projectName,
    pollExperimentCompletion,
  ]);

  const runAllViaFrontend = useCallback(async () => {
    resetState();
    isToStopRef.current = false;
    setAllRunning(true);

    const logProcessor = createLogPlaygroundProcessor({
      ...logProcessorHandlers,
      projectName,
    });

    const combinations = createCombinations();
    const totalCombinations = combinations.length;

    // Initialize progress tracking
    setProgress(0, totalCombinations);

    let completedCount = 0;

    asyncLib.mapLimit(
      combinations,
      maxConcurrentRequests,
      async (combination: DatasetItemPromptCombination) => {
        await processCombination(combination, logProcessor);

        // Update progress after each combination completes
        completedCount += 1;
        setProgress(completedCount, totalCombinations);
      },
      () => {
        // Signal that all logs have been sent
        logProcessor.finishLogging();

        clearRunningMap();
        isToStopRef.current = false;
        abortControllersRef.current.clear();
      },
    );
  }, [
    resetState,
    setAllRunning,
    clearRunningMap,
    createCombinations,
    processCombination,
    logProcessorHandlers,
    maxConcurrentRequests,
    setProgress,
    projectName,
  ]);

  const runAll = useCallback(async () => {
    if (isTestSuite) {
      return runAllViaBackend();
    }
    return runAllViaFrontend();
  }, [isTestSuite, runAllViaBackend, runAllViaFrontend]);

  const runSingle = useCallback(
    async (promptId: string) => {
      const prompt = usePlaygroundStore.getState().promptMap[promptId];
      if (!prompt) return;

      isToStopRef.current = false;
      setPromptRunning(promptId, true);
      const logProcessor = createLogPlaygroundProcessor({
        ...logProcessorHandlers,
        projectName,
      });

      try {
        await processCombination({ prompt }, logProcessor);
      } finally {
        logProcessor.finishLogging();
        setPromptRunning(promptId, false);
      }
    },
    [setPromptRunning, logProcessorHandlers, processCombination, projectName],
  );

  return {
    isRunning,
    runAll,
    runSingle,
    stopAll,
    stopSingle,
  };
};

export default useActionButtonActions;
