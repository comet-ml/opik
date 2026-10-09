import { create } from "zustand";
import { persist } from "zustand/middleware";
import pick from "lodash/pick";
import mapValues from "lodash/mapValues";
import union from "lodash/union";
import isEqual from "fast-deep-equal";

import {
  LogExperiment,
  PlaygroundPromptType,
  PlaygroundRunInputChange,
} from "@/types/playground";
import { LLMMessage } from "@/types/llm";
import { restoreMissingConfigKeys } from "@/lib/playground";
import { buildExperimentName } from "@/lib/experiments";
import { JsonObject } from "@/types/shared";
import { Filters } from "@/types/filters";
import { DATASET_TYPE } from "@/types/datasets";
import isUndefined from "lodash/isUndefined";

interface PlaygroundOutput {
  isLoading: boolean;
  value: string | null;
  error?: string;
  stale: boolean;
  staleChanges?: PlaygroundRunInputChange[];
  traceId?: string;
  selectedRuleIds?: string[] | null;
  usage?: {
    duration?: number;
    totalTokens?: number;
    model?: string;
    provider?: string;
  };
}

interface PlaygroundOutputMap {
  [promptId: string]: PlaygroundOutput;
}

// Returns the same object when nothing new changed, so cells subscribed to an
// already stale output don't re-render on every keystroke in the prompt.
const markOutputStale = (
  output: PlaygroundOutput,
  changes: PlaygroundRunInputChange[],
): PlaygroundOutput => {
  const previousChanges = output.stale ? output.staleChanges ?? [] : [];
  const staleChanges = union(previousChanges, changes);

  if (output.stale && staleChanges.length === previousChanges.length) {
    return output;
  }

  return { ...output, stale: true, staleChanges };
};

const markPromptOutputStale = (
  promptId: string,
  outputMap: PlaygroundOutputMap,
  changes: PlaygroundRunInputChange[],
) => {
  const promptOutput = outputMap[promptId];

  if (!promptOutput) {
    return outputMap;
  }

  return {
    ...outputMap,
    [promptId]: markOutputStale(promptOutput, changes),
  };
};

// Only what a run sends. Library links, message ids and one-off flags also go
// through updatePrompt, often with no user edit (a reload re-applies the loaded
// prompt), and must not hide the output.
const toRunMessages = (messages: LLMMessage[]) =>
  messages.map(({ role, content }) => ({ role, content }));

const getRunInputChanges = (
  prompt: PlaygroundPromptType,
  updatedPrompt: PlaygroundPromptType,
): PlaygroundRunInputChange[] => {
  const changes: PlaygroundRunInputChange[] = [];

  if (
    !isEqual(
      toRunMessages(prompt.messages),
      toRunMessages(updatedPrompt.messages),
    )
  ) {
    changes.push("prompt");
  }

  if (
    prompt.model !== updatedPrompt.model ||
    prompt.provider !== updatedPrompt.provider
  ) {
    changes.push("model");
  } else if (!isEqual(prompt.configs, updatedPrompt.configs)) {
    // Not counted on a model switch: it swaps in the new model's default
    // parameters, which the user didn't change themselves.
    changes.push("parameters");
  }

  return changes;
};

export type PlaygroundStore = {
  lastActiveProjectId: string | null;
  promptIds: string[];
  promptMap: Record<string, PlaygroundPromptType>;
  outputMap: PlaygroundOutputMap;
  datasetVariables: string[];
  datasetSampleData: JsonObject | null;
  providerValidationTrigger: number;
  selectedRuleIds: string[] | null;
  createdExperiments: LogExperiment[];
  isRunning: boolean; // v1 playground compatibility
  isRunningMap: Record<string, boolean>;
  datasetFilters: Filters;
  datasetPage: number;
  datasetSize: number;
  progressTotal: number;
  progressCompleted: number;
  progressPhase: "running" | "evaluating" | null;
  experimentName: string | null;
  lastSuggestedExperimentName: string | null;
  datasetType: DATASET_TYPE | null;
  experimentByPromptId: Record<string, string>;
  runTotalItems: number;
  datasetItemsTotal: number | null;
  hasUnseenRunCompletion: boolean;
  isRunInFlight: boolean;
  /** True from mount until a reloaded page has learned whether its run is still going. */
  isResumingRun: boolean;
  scoresByDatasetId: Record<string, string[] | null>;

  setPromptMap: (
    promptIds: string[],
    promptMap: Record<string, PlaygroundPromptType>,
  ) => void;
  updatePrompt: (
    promptId: string,
    changes: Partial<PlaygroundPromptType>,
  ) => void;
  addPrompt: (prompt: PlaygroundPromptType, position?: number) => void;
  deletePrompt: (promptId: string) => void;
  resetOutputMap: () => void;
  updateOutput: (promptId: string, changes: Partial<PlaygroundOutput>) => void;
  updateOutputTraceId: (promptId: string, traceId: string) => void;
  setDatasetVariables: (variables: string[]) => void;
  setDatasetSampleData: (data: JsonObject | null) => void;
  triggerProviderValidation: () => void;
  setSelectedRuleIds: (ruleIds: string[] | null) => void;
  setCreatedExperiments: (experiments: LogExperiment[]) => void;
  setRunTotalItems: (total: number) => void;
  setDatasetItemsTotal: (total: number | null) => void;
  setHasUnseenRunCompletion: (value: boolean) => void;
  setIsRunInFlight: (value: boolean) => void;
  clearCreatedExperiments: () => void;
  setIsRunning: (isRunning: boolean) => void;
  setPromptRunning: (promptId: string, running: boolean) => void;
  setAllRunning: (running: boolean) => void;
  clearRunningMap: () => void;
  settleRun: () => void;
  setIsResumingRun: (value: boolean) => void;
  setExperimentName: (name: string | null) => void;
  setSuggestedExperimentName: (name: string) => void;
  setDatasetFilters: (filters: Filters) => void;
  setDatasetPage: (page: number) => void;
  setDatasetSize: (size: number) => void;
  resetDatasetFilters: () => void;
  setProgress: (completed: number, total: number) => void;
  setProgressPhase: (phase: "running" | "evaluating" | null) => void;
  resetProgress: () => void;
  setLastActiveProjectId: (projectId: string | null) => void;
  setDatasetType: (type: DATASET_TYPE | null) => void;
  setExperimentByPromptId: (map: Record<string, string>) => void;
  setScoresForDataset: (datasetId: string, ruleIds: string[] | null) => void;
};

const usePlaygroundStore = create<PlaygroundStore>()(
  persist(
    (set) => ({
      lastActiveProjectId: null,
      promptIds: [],
      promptMap: {},
      outputMap: {},
      datasetVariables: [],
      datasetSampleData: null,
      providerValidationTrigger: 0,
      selectedRuleIds: null,
      createdExperiments: [],
      isRunning: false,
      isRunningMap: {},
      datasetFilters: [],
      datasetPage: 1,
      datasetSize: 100,
      progressTotal: 0,
      progressCompleted: 0,
      progressPhase: null,
      experimentName: null,
      lastSuggestedExperimentName: null,
      datasetType: null,
      experimentByPromptId: {},
      runTotalItems: 0,
      datasetItemsTotal: null,
      hasUnseenRunCompletion: false,
      isRunInFlight: false,
      isResumingRun: false,
      scoresByDatasetId: {},

      updatePrompt: (promptId, changes) => {
        set((state) => {
          const prompt = state.promptMap[promptId];
          const updatedPrompt = { ...prompt, ...changes };
          const runInputChanges = getRunInputChanges(prompt, updatedPrompt);

          return {
            ...state,
            promptMap: {
              ...state.promptMap,
              [promptId]: updatedPrompt,
            },
            outputMap: runInputChanges.length
              ? markPromptOutputStale(
                  promptId,
                  state.outputMap,
                  runInputChanges,
                )
              : state.outputMap,
          };
        });
      },
      setPromptMap: (promptIds, promptMap) => {
        set((state) => {
          return {
            ...state,
            promptIds,
            promptMap,
            outputMap: pick(state.outputMap, promptIds),
          };
        });
      },
      addPrompt: (prompt, position) => {
        set((state) => {
          const newPromptIds = [...state.promptIds];
          const pos = !isUndefined(position) ? position : newPromptIds.length;

          newPromptIds.splice(pos, 0, prompt.id);

          return {
            ...state,
            promptIds: newPromptIds,
            promptMap: {
              ...state.promptMap,
              [prompt.id]: prompt,
            },
          };
        });
      },
      deletePrompt: (promptId) => {
        set((state) => {
          const newPromptIds = state.promptIds.filter((id) => id !== promptId);
          const newPromptMap = { ...state.promptMap };

          delete newPromptMap[promptId];

          return {
            ...state,
            promptIds: newPromptIds,
            promptMap: newPromptMap,
            outputMap: pick(state.outputMap, newPromptIds),
          };
        });
      },
      resetOutputMap: () => {
        set((state) => {
          return {
            ...state,
            outputMap: {},
          };
        });
      },
      updateOutput: (promptId, changes: Partial<PlaygroundOutput>) => {
        set((state) => ({
          ...state,
          outputMap: {
            ...state.outputMap,
            [promptId]: {
              ...state.outputMap[promptId],
              stale: false,
              staleChanges: undefined,
              ...changes,
            },
          },
        }));
      },
      updateOutputTraceId: (promptId, traceId) => {
        set((state) => {
          const output = state.outputMap[promptId];
          if (!output) return state;

          return {
            ...state,
            outputMap: {
              ...state.outputMap,
              [promptId]: { ...output, traceId },
            },
          };
        });
      },
      setDatasetVariables: (variables) => {
        set((state) => {
          return {
            ...state,
            datasetVariables: variables,
          };
        });
      },
      setDatasetSampleData: (data) => {
        set((state) => {
          return {
            ...state,
            datasetSampleData: data,
          };
        });
      },
      triggerProviderValidation: () => {
        set((state) => {
          return {
            ...state,
            providerValidationTrigger: state.providerValidationTrigger + 1,
          };
        });
      },
      setSelectedRuleIds: (ruleIds) => {
        set((state) => {
          return {
            ...state,
            selectedRuleIds: ruleIds,
          };
        });
      },
      setRunTotalItems: (total) => {
        set((state) => ({ ...state, runTotalItems: total }));
      },
      setDatasetItemsTotal: (total) => {
        set((state) => ({ ...state, datasetItemsTotal: total }));
      },
      setHasUnseenRunCompletion: (value) => {
        set((state) => ({ ...state, hasUnseenRunCompletion: value }));
      },
      setIsRunInFlight: (value) => {
        set((state) => ({ ...state, isRunInFlight: value }));
      },
      setCreatedExperiments: (experiments) => {
        set((state) => {
          return {
            ...state,
            createdExperiments: experiments,
          };
        });
      },
      clearCreatedExperiments: () => {
        set((state) => {
          return {
            ...state,
            createdExperiments: [],
            experimentByPromptId: {},
            runTotalItems: 0,
          };
        });
      },
      setIsRunning: (isRunning) => {
        set((state) => ({ ...state, isRunning }));
      },
      setPromptRunning: (promptId, running) => {
        set((state) => ({
          ...state,
          isRunningMap: { ...state.isRunningMap, [promptId]: running },
        }));
      },
      setAllRunning: (running) => {
        set((state) => {
          const map: Record<string, boolean> = {};
          state.promptIds.forEach((id) => {
            map[id] = running;
          });
          return { ...state, isRunningMap: map };
        });
      },
      clearRunningMap: () => {
        set((state) => ({ ...state, isRunningMap: {} }));
      },
      settleRun: () => {
        set((state) => ({
          ...state,
          isRunningMap: {},
          isRunInFlight: false,
          isResumingRun: false,
        }));
      },
      setIsResumingRun: (value) => {
        set((state) => ({ ...state, isResumingRun: value }));
      },
      setExperimentName: (name) => {
        set((state) => ({
          ...state,
          experimentName: name,
          lastSuggestedExperimentName: null,
        }));
      },
      setSuggestedExperimentName: (name) => {
        set((state) => ({
          ...state,
          experimentName: name,
          lastSuggestedExperimentName: name,
        }));
      },
      setDatasetFilters: (filters) => {
        set((state) => {
          return {
            ...state,
            datasetFilters: filters,
          };
        });
      },
      setDatasetPage: (page) => {
        set((state) => {
          return {
            ...state,
            datasetPage: page,
          };
        });
      },
      setDatasetSize: (size) => {
        set((state) => {
          return {
            ...state,
            datasetSize: size,
          };
        });
      },
      resetDatasetFilters: () => {
        set((state) => {
          return {
            ...state,
            datasetFilters: [],
            datasetPage: 1,
            datasetSize: 100,
          };
        });
      },
      setProgress: (completed, total) => {
        set((state) => {
          return {
            ...state,
            progressCompleted: completed,
            progressTotal: total,
          };
        });
      },
      setProgressPhase: (phase) => {
        set((state) => ({ ...state, progressPhase: phase }));
      },
      resetProgress: () => {
        set((state) => {
          return {
            ...state,
            progressCompleted: 0,
            progressTotal: 0,
            progressPhase: null,
          };
        });
      },
      setLastActiveProjectId: (projectId) => {
        set((state) => ({ ...state, lastActiveProjectId: projectId }));
      },
      setDatasetType: (type) => {
        set((state) => ({ ...state, datasetType: type }));
      },
      setExperimentByPromptId: (map) => {
        set((state) => {
          // Test suites run on the BE, so updateOutput is never called and outputMap
          // entries are never created. Seed empty entries so stale tracking works
          // when the user changes prompt settings after a run.
          const newOutputMap = { ...state.outputMap };
          for (const promptId of Object.keys(map)) {
            newOutputMap[promptId] ??= {
              isLoading: false,
              value: null,
              stale: false,
            };
          }
          return {
            ...state,
            experimentByPromptId: map,
            outputMap: newOutputMap,
          };
        });
      },
      setScoresForDataset: (datasetId, ruleIds) => {
        set((state) => ({
          ...state,
          scoresByDatasetId: {
            ...state.scoresByDatasetId,
            [datasetId]: ruleIds,
          },
        }));
      },
    }),
    {
      name: "PLAYGROUND_STATE",
      // Normalizes on every load rather than through `migrate`: persisted blobs carry no version
      // (this store never set one), and zustand only migrates a blob whose stored version is a
      // number — so a migrate hook would skip exactly the states that need repairing. Cheap and
      // idempotent, since restoreMissingConfigKeys returns the prompt untouched when it is complete.
      merge: (persisted, current) => {
        const state = {
          ...current,
          ...(persisted as Partial<PlaygroundStore>),
        };

        if (!state.promptMap) {
          return state;
        }

        return {
          ...state,
          promptMap: mapValues(state.promptMap, restoreMissingConfigKeys),
        };
      },
      partialize: (state) => {
        /* eslint-disable @typescript-eslint/no-unused-vars */
        const {
          datasetSampleData,
          progressPhase,
          progressTotal,
          progressCompleted,
          datasetItemsTotal,
          isRunning,
          isRunningMap,
          isResumingRun,
          ...rest
        } = state;
        /* eslint-enable @typescript-eslint/no-unused-vars */

        // skipInitialPromptLoad is only meaningful within a single session
        const cleanedPromptMap = Object.fromEntries(
          Object.entries(rest.promptMap).map(([id, prompt]) => {
            if (!prompt.skipInitialPromptLoad) return [id, prompt];
            // eslint-disable-next-line @typescript-eslint/no-unused-vars
            const { skipInitialPromptLoad, ...cleanPrompt } = prompt;
            return [id, cleanPrompt];
          }),
        );

        return { ...rest, promptMap: cleanedPromptMap };
      },
    },
  ),
);

export const useOutputByPromptId = (promptId: string) =>
  usePlaygroundStore((state) => state.outputMap?.[promptId] ?? null);

export const useIsPromptOutputStale = (promptId: string) =>
  usePlaygroundStore((state) => state.outputMap?.[promptId]?.stale ?? false);

export const usePromptMap = () =>
  usePlaygroundStore((state) => state.promptMap);

export const usePromptById = (id: string) =>
  usePlaygroundStore((state) => state.promptMap[id]);

export const usePromptIds = () =>
  usePlaygroundStore((state) => state.promptIds);

export const usePromptCount = () =>
  usePlaygroundStore((state) => state.promptIds.length);

export const useSetPromptMap = () =>
  usePlaygroundStore((state) => state.setPromptMap);

export const useUpdatePrompt = () =>
  usePlaygroundStore((state) => state.updatePrompt);

export const useAddPrompt = () =>
  usePlaygroundStore((state) => state.addPrompt);

export const useDeletePrompt = () =>
  usePlaygroundStore((state) => state.deletePrompt);

export const useResetOutputMap = () =>
  usePlaygroundStore((state) => state.resetOutputMap);

export const useUpdateOutput = () =>
  usePlaygroundStore((state) => state.updateOutput);

export const useUpdateOutputTraceId = () =>
  usePlaygroundStore((state) => state.updateOutputTraceId);

export const useDatasetVariables = () =>
  usePlaygroundStore((state) => state.datasetVariables);

export const useSetDatasetVariables = () =>
  usePlaygroundStore((state) => state.setDatasetVariables);

export const useDatasetSampleData = () =>
  usePlaygroundStore((state) => state.datasetSampleData);

export const useSetDatasetSampleData = () =>
  usePlaygroundStore((state) => state.setDatasetSampleData);

export const useProviderValidationTrigger = () =>
  usePlaygroundStore((state) => state.providerValidationTrigger);

export const useTriggerProviderValidation = () =>
  usePlaygroundStore((state) => state.triggerProviderValidation);

export const useSelectedRuleIds = () =>
  usePlaygroundStore((state) => state.selectedRuleIds);

export const useSetSelectedRuleIds = () =>
  usePlaygroundStore((state) => state.setSelectedRuleIds);

export const useCreatedExperiments = () =>
  usePlaygroundStore((state) => state.createdExperiments);

export const useSetCreatedExperiments = () =>
  usePlaygroundStore((state) => state.setCreatedExperiments);

export const useSetRunTotalItems = () =>
  usePlaygroundStore((state) => state.setRunTotalItems);

export const useDatasetItemsTotal = () =>
  usePlaygroundStore((state) => state.datasetItemsTotal);

export const useIsRunInFlight = () =>
  usePlaygroundStore((state) => state.isRunInFlight);

export const useSetIsRunInFlight = () =>
  usePlaygroundStore((state) => state.setIsRunInFlight);

export const useHasUnseenRunCompletion = () =>
  usePlaygroundStore((state) => state.hasUnseenRunCompletion);

export const useSetHasUnseenRunCompletion = () =>
  usePlaygroundStore((state) => state.setHasUnseenRunCompletion);

export const useSetDatasetItemsTotal = () =>
  usePlaygroundStore((state) => state.setDatasetItemsTotal);

export const useClearCreatedExperiments = () =>
  usePlaygroundStore((state) => state.clearCreatedExperiments);

// Reads both v1 (boolean) and v2 (per-prompt map) running state
// for compatibility with v1 playground
export const useIsRunning = () =>
  usePlaygroundStore(
    (state) =>
      state.isRunning || Object.values(state.isRunningMap).some(Boolean),
  );

export const useSetIsRunning = () =>
  usePlaygroundStore((state) => state.setIsRunning);

export const useIsPromptRunning = (promptId: string) =>
  usePlaygroundStore((state) => !!state.isRunningMap[promptId]);

export const useSetPromptRunning = () =>
  usePlaygroundStore((state) => state.setPromptRunning);

export const useSetAllRunning = () =>
  usePlaygroundStore((state) => state.setAllRunning);

export const useClearRunningMap = () =>
  usePlaygroundStore((state) => state.clearRunningMap);

export const useSettleRun = () =>
  usePlaygroundStore((state) => state.settleRun);

export const useIsResumingRun = () =>
  usePlaygroundStore((state) => state.isResumingRun);

export const useSetIsResumingRun = () =>
  usePlaygroundStore((state) => state.setIsResumingRun);

export const useExperimentName = () =>
  usePlaygroundStore((state) => state.experimentName);

export const useSetExperimentName = () =>
  usePlaygroundStore((state) => state.setExperimentName);

export const useLastSuggestedExperimentName = () =>
  usePlaygroundStore((state) => state.lastSuggestedExperimentName);

export const useSetSuggestedExperimentName = () =>
  usePlaygroundStore((state) => state.setSuggestedExperimentName);

export const useDatasetFilters = () =>
  usePlaygroundStore((state) => state.datasetFilters);

export const useSetDatasetFilters = () =>
  usePlaygroundStore((state) => state.setDatasetFilters);

export const useDatasetPage = () =>
  usePlaygroundStore((state) => state.datasetPage);

export const useSetDatasetPage = () =>
  usePlaygroundStore((state) => state.setDatasetPage);

export const useDatasetSize = () =>
  usePlaygroundStore((state) => state.datasetSize);

export const useSetDatasetSize = () =>
  usePlaygroundStore((state) => state.setDatasetSize);

export const useResetDatasetFilters = () =>
  usePlaygroundStore((state) => state.resetDatasetFilters);

export const useProgressTotal = () =>
  usePlaygroundStore((state) => state.progressTotal);

export const useProgressCompleted = () =>
  usePlaygroundStore((state) => state.progressCompleted);

export const useSetProgress = () =>
  usePlaygroundStore((state) => state.setProgress);

export const useProgressPhase = () =>
  usePlaygroundStore((state) => state.progressPhase);

export const useSetProgressPhase = () =>
  usePlaygroundStore((state) => state.setProgressPhase);

export const useResetProgress = () =>
  usePlaygroundStore((state) => state.resetProgress);

export const useLastActiveProjectId = () =>
  usePlaygroundStore((state) => state.lastActiveProjectId);

export const useSetLastActiveProjectId = () =>
  usePlaygroundStore((state) => state.setLastActiveProjectId);

export const useDatasetType = () =>
  usePlaygroundStore((state) => state.datasetType);

export const useSetDatasetType = () =>
  usePlaygroundStore((state) => state.setDatasetType);

export const useExperimentByPromptId = () =>
  usePlaygroundStore((state) => state.experimentByPromptId);

export const useExperimentIdByPromptId = (promptId: string) =>
  usePlaygroundStore((state) => state.experimentByPromptId[promptId] ?? null);

export const useSetExperimentByPromptId = () =>
  usePlaygroundStore((state) => state.setExperimentByPromptId);

export const useScoresByDatasetId = () =>
  usePlaygroundStore((state) => state.scoresByDatasetId);

export const useSetScoresForDataset = () =>
  usePlaygroundStore((state) => state.setScoresForDataset);

export const getExperimentNameForPrompt = (promptId: string) => {
  const { experimentName, promptIds } = usePlaygroundStore.getState();
  if (!experimentName) return undefined;

  return buildExperimentName(experimentName, promptIds.indexOf(promptId));
};

export const getExperimentNamesForPrompts = (
  ids: string[],
): Record<string, string | undefined> =>
  Object.fromEntries(ids.map((id) => [id, getExperimentNameForPrompt(id)]));

export default usePlaygroundStore;
