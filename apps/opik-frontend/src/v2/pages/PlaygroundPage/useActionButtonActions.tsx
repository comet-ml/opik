import { useCallback, useEffect, useMemo, useRef } from "react";
import asyncLib from "async";
import { useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import { getExperimentById } from "@/api/datasets/useExperimentById";

import { COMPARE_EXPERIMENTS_KEY, PROJECTS_KEY } from "@/api/api";
import { getCompareExperimentsList } from "@/api/datasets/useCompareExperimentsList";
import {
  ASSERTION_POLL_INTERVAL_MS,
  COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
  EXPERIMENT_POLL_INTERVAL_MS,
} from "@/constants/experiments";
import {
  DATASET_TYPE,
  EVALUATION_METHOD,
  ExperimentsCompare,
} from "@/types/datasets";
import { isExperimentTerminal } from "@/lib/experiments";
import { processFilters, transformDataColumnFilters } from "@/lib/filters";
import { isItemScored } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useTestSuitePromptResults";
import { LogExperiment } from "@/types/playground";
import useRunExperimentExecution from "@/api/playground/useRunExperimentExecution";
import useCancelExperimentExecution from "@/api/playground/useCancelExperimentExecution";
import usePlaygroundStore, {
  getExperimentNameForPrompt,
  getExperimentNamesForPrompts,
  usePromptIds,
  usePromptMap,
  useResetOutputMap,
  useSelectedRuleIds,
  useSetCreatedExperiments,
  useSetRunTotalItems,
  useSetIsRunInFlight,
  useClearCreatedExperiments,
  useIsRunning,
  useSetAllRunning,
  useSetIsResumingRun,
  useSettleRun,
  useSetPromptRunning,
  useSetProgress,
  useSetProgressPhase,
  useResetProgress,
  useUpdateOutputTraceId,
  useDatasetType,
  useDatasetFilters,
  useSetExperimentByPromptId,
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
import useRunCompletionToast from "@/v2/pages/PlaygroundPage/useRunCompletionToast";
import useOpenAiPipelineMode from "@/hooks/useOpenAiPipelineMode";

const DEFAULT_MAX_CONCURRENT_REQUESTS = 5;

const MAX_POLL_DURATION_MS = 5 * 60 * 1000;
const MAX_POLL_DURATION_LABEL = "5 minutes";

/**
 * What this tab stops doing after {@link MAX_POLL_DURATION_LABEL}, which is watching — the run
 * itself is on the server and finishes whether or not anyone is looking.
 */
const STILL_RUNNING_DESCRIPTION =
  `This run is taking longer than ${MAX_POLL_DURATION_LABEL}, so it is no longer being followed here. ` +
  "It continues on the server — reopen the playground later to see the results.";

interface PollScope {
  scopedPromptIds?: string[];
  pollKeySuffix?: string;
  announceExperiments?: LogExperiment[];
}

interface UseActionButtonActionsArguments {
  workspaceName: string;
  datasetName: string | null;
  datasetVersionId?: string;
  datasetId?: string;
  versionHash?: string;
  projectName?: string;
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
}: UseActionButtonActionsArguments) => {
  const queryClient = useQueryClient();

  const { toast } = useToast();

  const {
    permissions: { canLogTraceSpanThread },
  } = usePermissions();

  const isRunning = useIsRunning();
  const setAllRunning = useSetAllRunning();
  const settleRun = useSettleRun();
  const setIsResumingRun = useSetIsResumingRun();
  const setPromptRunning = useSetPromptRunning();
  const isToStopRef = useRef(false);
  const setCreatedExperiments = useSetCreatedExperiments();
  const setRunTotalItems = useSetRunTotalItems();
  const setIsRunInFlight = useSetIsRunInFlight();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const promptIds = usePromptIds();
  const promptMap = usePromptMap();
  const selectedRuleIds = useSelectedRuleIds();
  const datasetType = useDatasetType();
  const datasetFilters = useDatasetFilters();
  const setExperimentByPromptId = useSetExperimentByPromptId();
  const abortControllersRef = useRef(
    new Map<string, { controller: AbortController; promptId: string }>(),
  );
  const runExperimentExecution = useRunExperimentExecution();
  const openAiPipelineMode = useOpenAiPipelineMode(workspaceName);

  // Only `mutate` is stable; useMutation's object is new every render. stopAll ends up in an
  // unmount cleanup, which an unstable identity re-runs every render, stopping the run it began.
  const { mutate: cancelExperimentRun } = useCancelExperimentExecution();
  const announceRunComplete = useRunCompletionToast(datasetId);
  const announcePendingRef = useRef(false);
  const scopedAnnounceRef = useRef(new Set<string>());

  const isTestSuite = isTestSuiteRun(datasetId, datasetType);
  const evaluationMethod = isTestSuite
    ? EVALUATION_METHOD.TEST_SUITE
    : EVALUATION_METHOD.DATASET;

  // Keyed on the id alone: the name arrives from its own request, and falling back to the browser
  // while that is in flight would quietly give one run different semantics from the next.
  const isBackendRun = !!datasetId;

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
    settleRun();
    clearCreatedExperiments();
    resetProgress();
  }, [resetOutputMap, clearCreatedExperiments, settleRun, resetProgress]);

  // isRunInFlight is shared by every prompt, and the sidebar watches the run through it. An action
  // scoped to one prompt may only clear it once no sibling is left running.
  const releaseRunInFlight = useCallback(() => {
    const { isRunningMap } = usePlaygroundStore.getState();
    if (!Object.values(isRunningMap).some(Boolean)) {
      setIsRunInFlight(false);
    }
  }, [setIsRunInFlight]);

  const cancelBackendRun = useCallback(
    (promptIds?: string[]) => {
      if (!isBackendRun) return;

      const experimentByPromptId =
        usePlaygroundStore.getState().experimentByPromptId ?? {};
      const experimentIds = (promptIds ?? Object.keys(experimentByPromptId))
        .map((promptId) => experimentByPromptId[promptId])
        .filter(Boolean);

      if (experimentIds.length === 0) return;

      releaseRunInFlight();
      cancelExperimentRun({ experimentIds });
      queryClient.invalidateQueries({ queryKey: ["experiments"] });
      queryClient.invalidateQueries({ queryKey: ["experiment"] });
      queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
    },
    [isBackendRun, cancelExperimentRun, releaseRunInFlight, queryClient],
  );

  /**
   * Leaving the page, as opposed to stopping the run. A server-side run outlives it, so cancelling
   * here would end the run, and clearing the in-flight flag would stop the sidebar watching for it.
   */
  const stopWatching = useCallback(() => {
    abortControllersRef.current.forEach(({ controller }) => controller.abort());
    abortControllersRef.current.clear();
    // The poll is a timeout chain and outlives the page; left running it settles the run here.
    isToStopRef.current = true;

    if (!isBackendRun) {
      settleRun();
    }
  }, [settleRun, isBackendRun]);

  const stopAll = useCallback(() => {
    announcePendingRef.current = false;
    scopedAnnounceRef.current.clear();
    settleRun();
    isToStopRef.current = true;
    abortControllersRef.current.forEach(({ controller }) => controller.abort());
    abortControllersRef.current.clear();
    cancelBackendRun();
  }, [settleRun, cancelBackendRun]);

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

  const storeExperiments = useCallback(
    (experiments: LogExperiment[]) => {
      setCreatedExperiments(experiments);
    },
    [setCreatedExperiments],
  );

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

  const { createCombinations, processCombination } = usePromptCombination({
    workspaceName,
    selectedRuleIds,
    addAbortController,
    deleteAbortController,
    throttlingSeconds,
    openAiPipelineMode,
  });

  const handlePollTimeout = useCallback(
    (description: string) => {
      announcePendingRef.current = false;
      settleRun();
      isToStopRef.current = false;
      resetProgress();
      queryClient.invalidateQueries({ queryKey: ["experiments"] });
      queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
      toast({ title: "Still running", description });
    },
    [settleRun, resetProgress, queryClient, toast],
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

      const poll = async () => {
        if (!isScoped && isToStopRef.current) return;

        if (Date.now() - startTime > MAX_POLL_DURATION_MS) {
          if (isScoped) {
            finishPollScope(scope);
            toast({
              title: "Still running",
              description: STILL_RUNNING_DESCRIPTION,
            });
          } else {
            handlePollTimeout(STILL_RUNNING_DESCRIPTION);
          }
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
            queryClient.invalidateQueries({ queryKey: ["experiments"] });
            queryClient.invalidateQueries({
              queryKey: [COMPARE_EXPERIMENTS_KEY],
            });
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
      handlePollTimeout,
      announceRunComplete,
      finishPollScope,
      queryClient,
      toast,
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

      const poll = async () => {
        if (!isScoped && isToStopRef.current) return;

        if (Date.now() - startTime > MAX_POLL_DURATION_MS) {
          if (isScoped) {
            finishPollScope(scope);
            toast({
              title: "Still running",
              description: STILL_RUNNING_DESCRIPTION,
            });
          } else {
            handlePollTimeout(STILL_RUNNING_DESCRIPTION);
          }
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
            queryClient.invalidateQueries({ queryKey: ["experiments"] });
            queryClient.invalidateQueries({
              queryKey: [COMPARE_EXPERIMENTS_KEY],
            });
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
      announceRunComplete,
      handlePollTimeout,
      queryClient,
      pollAssertionEvaluation,
      toast,
      handlePollError,
      isTestSuite,
    ],
  );

  const runAllViaBackend = useCallback(async () => {
    if (!datasetId) return;
    if (!datasetName) return reportDatasetNotReady();

    resetState();
    isToStopRef.current = false;
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
      setRunTotalItems(response.total_items);
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
      isToStopRef.current = false;
    }
  }, [
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
    setRunTotalItems,
    setIsRunInFlight,
    queryClient,
    projectName,
    pollExperimentCompletion,
    reportDatasetNotReady,
    selectedRuleIds,
    datasetItemFilters,
    evaluationMethod,
  ]);

  const runAllViaFrontend = useCallback(async () => {
    resetState();
    isToStopRef.current = false;
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
        isToStopRef.current = false;
        abortControllersRef.current.clear();
      },
    );
  }, [
    resetState,
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

  const runAll = useCallback(async () => {
    if (isBackendRun) {
      return runAllViaBackend();
    }
    return runAllViaFrontend();
  }, [isBackendRun, runAllViaBackend, runAllViaFrontend]);

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

  const runSingleViaBackend = useCallback(
    async (promptId: string) => {
      if (!datasetId) return;
      if (!datasetName) return reportDatasetNotReady();
      const prompt = usePlaygroundStore.getState().promptMap[promptId];
      if (!prompt) return;

      setPromptRunning(promptId, true);
      scopedAnnounceRef.current.add(promptId);

      const experimentName = getExperimentNameForPrompt(prompt.id);

      try {
        const response = await runExperimentExecution.mutateAsync({
          datasetName,
          datasetVersionId,
          datasetId,
          versionHash,
          prompts: [prompt],
          projectName,
          experimentNames: { [prompt.id]: experimentName },
          openAiPipelineMode,
          selectedRuleIds,
          filters: datasetItemFilters,
        });

        const experiment = response.experiments[0];
        if (!experiment) {
          setPromptRunning(promptId, false);
          return;
        }

        if (!usePlaygroundStore.getState().isRunningMap[promptId]) {
          return;
        }

        const existing =
          usePlaygroundStore.getState().experimentByPromptId ?? {};
        setExperimentByPromptId({
          ...existing,
          [promptId]: experiment.experiment_id,
        });
        setRunTotalItems(response.total_items);
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
      }
    },
    [
      datasetName,
      datasetId,
      datasetVersionId,
      versionHash,
      projectName,
      runExperimentExecution,
      openAiPipelineMode,
      setPromptRunning,
      setExperimentByPromptId,
      setRunTotalItems,
      setIsRunInFlight,
      queryClient,
      pollExperimentCompletion,
      reportDatasetNotReady,
      selectedRuleIds,
      datasetItemFilters,
      evaluationMethod,
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

  // A server-side run outlives the tab, so reopening the playground has to pick it back up: the
  // experiment ids and the run's size are persisted, the progress bar and the running state are not.
  // Without this the run would finish unseen and the page would look idle while it worked.
  const hasResumedRef = useRef(false);
  useEffect(() => {
    if (hasResumedRef.current || !datasetId) return;

    const { experimentByPromptId, runTotalItems, createdExperiments } =
      usePlaygroundStore.getState();
    const entries = Object.entries(experimentByPromptId ?? {});
    if (entries.length === 0 || runTotalItems === 0) return;

    hasResumedRef.current = true;
    // Set before the round-trip, not after: until it answers, the page knows a run exists but not
    // whether it is still going, and offering Run in that window starts a second one alongside it.
    setIsResumingRun(true);
    const experimentIds = entries.map(([, experimentId]) => experimentId);

    (async () => {
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
        setProgress(Math.min(loggedTraces, runTotalItems), runTotalItems);
        pollExperimentCompletion(
          resumable.map(({ experimentId }) => experimentId),
          runTotalItems,
          datasetId,
          { announceExperiments: createdExperiments },
        );
      } catch {
        // A run we cannot read the status of is one we cannot resume; the cells still fill in on
        // their own, so leaving the page idle is better than a progress bar that never moves.
        // Settled rather than simply left alone: isRunInFlight is persisted, so giving up without
        // clearing it strands the sidebar watcher polling for a status that never arrives.
        settleRun();
      } finally {
        setIsResumingRun(false);
      }
    })();
  }, [
    datasetId,
    queryClient,
    setIsResumingRun,
    settleRun,
    setPromptRunning,
    setProgress,
    setProgressPhase,
    pollExperimentCompletion,
    setIsRunInFlight,
  ]);

  return {
    isRunning,
    runAll,
    runSingle,
    stopAll,
    stopWatching,
    stopSingle,
  };
};

export default useActionButtonActions;
