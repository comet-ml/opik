import { useCallback, useMemo, useRef } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";

import { PROJECTS_KEY } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { LogExperiment, PlaygroundPromptType } from "@/types/playground";
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
  useResetProgress,
  useUpdateOutputTraceId,
} from "@/store/PlaygroundStore";

import { useToast } from "@/ui/use-toast";
import createLogPlaygroundProcessor, {
  LogProcessorArgs,
  TraceMapping,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptDatasetItemCombination from "@/v2/pages/PlaygroundPage/usePromptDatasetItemCombination";

const DEFAULT_MAX_CONCURRENT_REQUESTS = 5;

interface DatasetItemPromptCombination {
  datasetItem?: DatasetItem;
  prompt: PlaygroundPromptType;
}

interface UseActionButtonActionsArguments {
  datasetItems: DatasetItem[];
  workspaceName: string;
  datasetName: string | null;
  datasetVersionId?: string;
}

const useActionButtonActions = ({
  datasetItems,
  workspaceName,
  datasetName,
  datasetVersionId,
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
  const abortControllersRef = useRef(new Map<string, AbortController>());

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
    if (abortControllersRef.current.size === 0) {
      return;
    }

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
      onAddExperimentRegistry: (experiments) => {
        // Only store experiments when all have been created
        if (experiments.length === promptIds.length) {
          storeExperiments(experiments);
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

  const runAll = useCallback(async () => {
    resetState();
    setAllRunning(true);
    clearCreatedExperiments();

    const logProcessor = createLogPlaygroundProcessor(logProcessorHandlers);

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
    clearCreatedExperiments,
    createCombinations,
    processCombination,
    logProcessorHandlers,
    maxConcurrentRequests,
    setProgress,
  ]);

  const runSingle = useCallback(
    async (promptId: string) => {
      const prompt = usePlaygroundStore.getState().promptMap[promptId];
      if (!prompt) return;

      setPromptRunning(promptId, true);
      const logProcessor = createLogPlaygroundProcessor(logProcessorHandlers);

      try {
        await processCombination({ prompt }, logProcessor);
      } finally {
        logProcessor.finishLogging();
        setPromptRunning(promptId, false);
      }
    },
    [setPromptRunning, logProcessorHandlers, processCombination],
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
