import React, { useCallback, useMemo, useRef, useState } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";

import { PROJECTS_KEY } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { LogExperiment, PlaygroundPromptType } from "@/types/playground";
import { usePromptIds, useResetOutputMap } from "@/store/PlaygroundStore";

import { useToast } from "@/components/ui/use-toast";
import createLogPlaygroundProcessor, {
  LogProcessorArgs,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptDatasetItemCombination from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/usePromptDatasetItemCombination";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import { ToastAction } from "@/components/ui/toast";

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
  const promptIds = usePromptIds();
  const abortControllersRef = useRef(new Map<string, AbortController>());

  const resetOutputMap = useResetOutputMap();

  const resetState = useCallback(() => {
    resetOutputMap();
    abortControllersRef.current.clear();
    setIsRunning(false);
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

  const showMessageExperimentsLogged = useCallback(
    (experiments: LogExperiment[]) => {
      const title =
        experiments.length === 1 ? "Experiment started" : "Experiments started";

      toast({
        title,
        description:
          "Analyze the results to identify strengths and weaknesses, then iterate by refining prompts, datasets, or evaluation rules to optimize your LLM application's performance.",
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="px-0"
            altText="Go to experiment"
            key="Go to experiment"
            onClick={() => {
              navigate({
                experimentIds: experiments.map((e) => e.id),
                datasetId: datasetId,
              });
            }}
          >
            {experiments.length === 1
              ? "Go to experiment"
              : "Compare experiments"}
          </ToastAction>,
        ],
      });
    },
    [datasetId, navigate, toast],
  );

  const logProcessorHandlers: LogProcessorArgs = useMemo(() => {
    return {
      onAddExperimentRegistry: (experiments) => {
        // to check if all experiments have been created
        if (experiments.length === promptIds.length) {
          showMessageExperimentsLogged(experiments);
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
  }, [queryClient, promptIds.length, showMessageExperimentsLogged, toast]);

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
      addAbortController,
      deleteAbortController,
    });

  const runAll = useCallback(async () => {
    resetState();
    setIsRunning(true);

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

  return {
    isRunning,
    runAll,
    stopAll,
  };
};

export default useActionButtonActions;
