import { useCallback, useMemo, useRef, useState } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";

import { PROJECTS_KEY } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { LogExperiment, PlaygroundPromptType } from "@/types/playground";
import {
  usePromptIds,
  useResetOutputMap,
  useSelectedRuleIds,
} from "@/store/PlaygroundStore";

import { useToast } from "@/components/ui/use-toast";
import createLogPlaygroundProcessor, {
  LogProcessorArgs,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptDatasetItemCombination from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/usePromptDatasetItemCombination";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";

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

  const [isRunning, setIsRunning] = useState(false);
  const [isToStop, setIsToStop] = useState(false);
  const [createdExperiments, setCreatedExperiments] = useState<LogExperiment[]>(
    [],
  );
  const promptIds = usePromptIds();
  const selectedRuleIds = useSelectedRuleIds();
  const abortControllersRef = useRef(new Map<string, AbortController>());

  const resetOutputMap = useResetOutputMap();

  const resetState = useCallback(() => {
    resetOutputMap();
    abortControllersRef.current.clear();
    setIsRunning(false);
    setCreatedExperiments([]); // Clear experiments when resetting
  }, [resetOutputMap]);

  const stopAll = useCallback(() => {
    // nothing to stop
    if (abortControllersRef.current.size === 0) {
      return;
    }

    setIsToStop(true);
    abortControllersRef.current.forEach((controller) => controller.abort());

    abortControllersRef.current.clear();
  }, []);

  const storeExperiments = useCallback((experiments: LogExperiment[]) => {
    setCreatedExperiments(experiments);
  }, []);

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
      onCreateTraces: () => {
        queryClient.invalidateQueries({
          queryKey: [PROJECTS_KEY],
        });
      },
    };
  }, [queryClient, promptIds.length, storeExperiments, toast]);

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
      isToStop,
      datasetItems,
      datasetName,
      selectedRuleIds,
      addAbortController,
      deleteAbortController,
    });

  const runAll = useCallback(async () => {
    resetState();
    setIsRunning(true);
    setCreatedExperiments([]); // Clear previous experiments when starting a new run

    const logProcessor = createLogPlaygroundProcessor(logProcessorHandlers);

    const combinations = createCombinations();

    asyncLib.mapLimit(
      combinations,
      LIMIT_STREAMING_CALLS,
      async (combination: DatasetItemPromptCombination) =>
        processCombination(combination, logProcessor),
      () => {
        setIsRunning(false);
        setIsToStop(false);
        abortControllersRef.current.clear();
      },
    );
  }, [
    resetState,
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
