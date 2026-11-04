import { useCallback, useMemo } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";

import { PROJECTS_KEY } from "@/api/api";
import usePlaygroundStore, {
  getExperimentNameForPrompt,
  usePromptMap,
  useSelectedRuleIds,
  useSetAllRunning,
  useSetPromptRunning,
  useSetProgress,
  useSettleRun,
  useUpdateOutputTraceId,
} from "@/store/PlaygroundStore";
import { useToast } from "@/ui/use-toast";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  LogProcessorArgs,
  TraceMapping,
  buildLogProcessor,
} from "@/api/playground/createLogPlaygroundProcessor";
import usePromptCombination, {
  PromptCombination,
} from "@/v2/pages/PlaygroundPage/usePromptCombination";
import useOpenAiPipelineMode from "@/hooks/useOpenAiPipelineMode";

const DEFAULT_MAX_CONCURRENT_REQUESTS = 5;

interface UseClientSideRunArguments {
  workspaceName: string;
  projectName?: string;
  resetState: () => void;
  /** The poller owns the stop flag; a client-side run starting clears it the same way. */
  markWatchStarted: () => void;
  addAbortController: (
    key: string,
    value: AbortController,
    promptId: string,
  ) => void;
  deleteAbortController: (key: string) => void;
  clearAbortControllers: () => void;
}

/**
 * Running prompts in this tab, which is what a playground without a dataset does. Every row is
 * streamed through the proxy here and logged from the browser, so the run lives and dies with the
 * page — unlike a dataset run, which is published to the server and outlives it.
 *
 * This is also where the per-prompt concurrency and throttling settings apply. They are controls
 * on this loop and have no server-side equivalent, which is why a dataset run hides them.
 *
 * Called on every render, dataset run or not — hooks cannot be conditional — so the settings and
 * the combination hook are built even when nothing will use them. Both are cheap, and this is the
 * behaviour the caller had before the split.
 */
export default function useClientSideRun({
  workspaceName,
  projectName,
  resetState,
  markWatchStarted,
  addAbortController,
  deleteAbortController,
  clearAbortControllers,
}: UseClientSideRunArguments) {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const {
    permissions: { canLogTraceSpanThread },
  } = usePermissions();

  const promptMap = usePromptMap();
  const selectedRuleIds = useSelectedRuleIds();
  const openAiPipelineMode = useOpenAiPipelineMode(workspaceName);

  const setAllRunning = useSetAllRunning();
  const settleRun = useSettleRun();
  const setPromptRunning = useSetPromptRunning();
  const setProgress = useSetProgress();
  const updateOutputTraceId = useUpdateOutputTraceId();

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

  const logProcessorHandlers: LogProcessorArgs = useMemo(() => {
    return {
      onError: (e) => {
        toast({
          title: "Error",
          variant: "destructive",
          description: e.message,
        });
      },
      onCreateTraces: (traces, mappings: TraceMapping[]) => {
        mappings.forEach((mapping) => {
          updateOutputTraceId(mapping.promptId, mapping.traceId);
        });

        queryClient.invalidateQueries({
          queryKey: [PROJECTS_KEY],
        });
      },
    };
  }, [queryClient, toast, updateOutputTraceId]);

  const { createCombinations, processCombination } = usePromptCombination({
    workspaceName,
    selectedRuleIds,
    addAbortController,
    deleteAbortController,
    throttlingSeconds,
    openAiPipelineMode,
  });

  const runAllViaFrontend = useCallback(async () => {
    resetState();
    markWatchStarted();
    setAllRunning(true);

    const logProcessor = buildLogProcessor({
      canLogTraceSpanThread,
      args: { ...logProcessorHandlers, projectName },
    });

    const combinations = createCombinations();
    const totalCombinations = combinations.length;

    // Initialize progress tracking
    setProgress(0, totalCombinations);

    let completedCount = 0;

    asyncLib.mapLimit(
      combinations,
      maxConcurrentRequests,
      async (combination: PromptCombination) => {
        await processCombination(combination, logProcessor);

        // Update progress after each combination completes
        completedCount += 1;
        setProgress(completedCount, totalCombinations);
      },
      () => {
        logProcessor.finishLogging();

        settleRun();
        markWatchStarted();
        clearAbortControllers();
      },
    );
  }, [
    resetState,
    markWatchStarted,
    clearAbortControllers,
    setAllRunning,
    settleRun,
    createCombinations,
    processCombination,
    logProcessorHandlers,
    maxConcurrentRequests,
    setProgress,
    projectName,
    canLogTraceSpanThread,
  ]);

  const runSingleViaFrontend = useCallback(
    async (promptId: string) => {
      const prompt = usePlaygroundStore.getState().promptMap[promptId];
      if (!prompt) return;

      setPromptRunning(promptId, true);

      const logProcessor = buildLogProcessor({
        canLogTraceSpanThread,
        args: { ...logProcessorHandlers, projectName },
      });

      const experimentName = getExperimentNameForPrompt(promptId);
      const combinations: PromptCombination[] = [{ prompt, experimentName }];

      try {
        await new Promise<void>((resolve) => {
          asyncLib.mapLimit(
            combinations,
            maxConcurrentRequests,
            async (combination: PromptCombination) => {
              await processCombination(combination, logProcessor);
            },
            () => resolve(),
          );
        });
      } finally {
        logProcessor.finishLogging();
        setPromptRunning(promptId, false);
      }
    },
    [
      maxConcurrentRequests,
      setPromptRunning,
      logProcessorHandlers,
      processCombination,
      projectName,
      canLogTraceSpanThread,
    ],
  );

  return { runAllViaFrontend, runSingleViaFrontend };
}
