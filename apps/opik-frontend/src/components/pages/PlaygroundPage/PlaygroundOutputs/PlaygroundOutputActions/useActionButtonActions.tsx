import { useCallback, useMemo, useRef } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";

import { PROJECTS_KEY } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { LogExperiment, PlaygroundPromptType } from "@/types/playground";
import {
  usePromptIds,
  useResetOutputMap,
  useSelectedRuleIds,
  useCreatedExperiments,
  useSetCreatedExperiments,
  useClearCreatedExperiments,
  useIsRunning,
  useSetIsRunning,
} from "@/store/PlaygroundStore";

import { useToast } from "@/components/ui/use-toast";
import createLogPlaygroundProcessor, {
  LogProcessorArgs,
  TraceMapping,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptDatasetItemCombination from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/usePromptDatasetItemCombination";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import { useUpdateOutputTraceId } from "@/store/PlaygroundStore";

const LIMIT_STREAMING_CALLS = 5;

interface DatasetItemPromptCombination {
  datasetItem?: DatasetItem;
  prompt: PlaygroundPromptType;
}

interface UseActionButtonActionsArguments {
  datasetItems: DatasetItem[];
  workspaceName: string;
  datasetName: string | null;
  datasetId?: string;
}

const useActionButtonActions = ({
  datasetItems,
  workspaceName,
  datasetName,
  datasetId,
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
  const selectedRuleIds = useSelectedRuleIds();
  const abortControllersRef = useRef(new Map<string, AbortController>());

  const resetOutputMap = useResetOutputMap();
  const updateOutputTraceId = useUpdateOutputTraceId();

  const resetState = useCallback(() => {
    resetOutputMap();
    abortControllersRef.current.clear();
    setIsRunning(false);
    clearCreatedExperiments();
  }, [resetOutputMap, clearCreatedExperiments, setIsRunning]);

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
      selectedRuleIds,
      addAbortController,
      deleteAbortController,
    });

  const runAll = useCallback(async () => {
    resetState();
    setIsRunning(true);
    clearCreatedExperiments();

    const logProcessor = createLogPlaygroundProcessor(logProcessorHandlers);

    const combinations = createCombinations();

    asyncLib.mapLimit(
      combinations,
      LIMIT_STREAMING_CALLS,
      async (combination: DatasetItemPromptCombination) =>
        processCombination(combination, logProcessor),
      () => {
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
