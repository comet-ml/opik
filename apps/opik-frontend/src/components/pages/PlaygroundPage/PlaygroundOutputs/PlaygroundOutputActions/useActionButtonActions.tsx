import { useCallback, useMemo, useRef } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";

import { PROJECTS_KEY } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { LogExperiment, PlaygroundPromptType } from "@/types/playground";
import {
  usePromptIds,
  usePromptMap,
  useResetOutputMap,
  useSelectedRuleIds,
  useCreatedExperiments,
  useSetCreatedExperiments,
  useClearCreatedExperiments,
  useIsRunning,
  useSetIsRunning,
  useSetProgress,
  useResetProgress,
} from "@/store/PlaygroundStore";

import { useToast } from "@/components/ui/use-toast";
import createLogPlaygroundProcessor, {
  LogProcessorArgs,
  TraceMapping,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptDatasetItemCombination from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/usePromptDatasetItemCombination";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import { useUpdateOutputTraceId } from "@/store/PlaygroundStore";

const DEFAULT_MAX_CONCURRENT_REQUESTS = 5;

interface DatasetItemPromptCombination {
  datasetItem?: DatasetItem;
  prompt: PlaygroundPromptType;
}

interface UseActionButtonActionsArguments {
  datasetItems: DatasetItem[];
  workspaceName: string;
  datasetName: string | null;
  datasetId?: string;
  datasetVersionId?: string;
}

const useActionButtonActions = ({
  datasetItems,
  workspaceName,
  datasetName,
  datasetId,
  datasetVersionId,
}: UseActionButtonActionsArguments) => {
  const queryClient = useQueryClient();
  const { navigate } = useNavigateToExperiment();

  const { toast } = useToast();

  const isRunning = useIsRunning();
  const setIsRunning = useSetIsRunning();
  const isToStopRef = useRef(false);
  const createdExperiments = useCreatedExperiments();
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
    setIsRunning(false);
    clearCreatedExperiments();
    resetProgress();
  }, [resetOutputMap, clearCreatedExperiments, setIsRunning, resetProgress]);

  const stopAll = useCallback(() => {
    setIsRunning(false);
    // nothing to stop
    if (abortControllersRef.current.size === 0) {
      return;
    }

    isToStopRef.current = true;
    abortControllersRef.current.forEach((controller) => controller.abort());
    abortControllersRef.current.clear();
  }, [setIsRunning]);

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
    setIsRunning(true);
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

        setIsRunning(false);
        isToStopRef.current = false;
        abortControllersRef.current.clear();
      },
    );
  }, [
    resetState,
    setIsRunning,
    clearCreatedExperiments,
    createCombinations,
    processCombination,
    logProcessorHandlers,
    maxConcurrentRequests,
    setProgress,
  ]);

  const navigateToExperiments = useCallback(() => {
    if (createdExperiments.length > 0) {
      navigate({
        experimentIds: createdExperiments.map((e) => e.id),
        datasetId: datasetId,
      });
    }
  }, [createdExperiments, datasetId, navigate]);

  return {
    isRunning,
    runAll,
    stopAll,
    createdExperiments,
    navigateToExperiments,
  };
};

export default useActionButtonActions;
