import { create } from "zustand";
import { persist } from "zustand/middleware";
import pick from "lodash/pick";

import { LogExperiment, PlaygroundPromptType } from "@/types/playground";
import { Filters } from "@/types/filters";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import lodashSet from "lodash/set";

interface PlaygroundOutput {
  isLoading: boolean;
  value: string | null;
  stale: boolean;
  traceId?: string;
  selectedRuleIds?: string[] | null;
}

interface PlaygroundOutputWithDatasetItem {
  datasetItemMap: {
    [datasetItemId: string]: PlaygroundOutput;
  };
}

interface PlaygroundOutputMap {
  [promptId: string]: PlaygroundOutput | PlaygroundOutputWithDatasetItem;
}

const isPlaygroundOutputWithDatasetItem = (
  output: PlaygroundOutput | PlaygroundOutputWithDatasetItem,
): output is PlaygroundOutputWithDatasetItem => {
  return "datasetItemMap" in output;
};

const updateAllStaleStatusesForPromptOutput = (
  promptId: string,
  outputMap: PlaygroundOutputMap,
  value: boolean,
) => {
  if (!outputMap[promptId]) {
    return outputMap;
  }

  const promptOutput = outputMap[promptId];

  if (!isPlaygroundOutputWithDatasetItem(promptOutput)) {
    const currentStaleStatus = promptOutput.stale;
    if (currentStaleStatus !== value) {
      return {
        ...outputMap,
        [promptId]: {
          ...promptOutput,
          stale: value,
        },
      };
    }
    return outputMap;
  }

  const datasetItemMap = promptOutput.datasetItemMap;
  const datasetItemIds = Object.keys(datasetItemMap);

  const updatedDatasetItemMap = datasetItemIds.reduce<
    PlaygroundOutputWithDatasetItem["datasetItemMap"]
  >((updatedMap, datasetItemId) => {
    const datasetItem = datasetItemMap[datasetItemId];
    const currentStaleStatus = datasetItem.stale;

    if (currentStaleStatus !== value) {
      updatedMap[datasetItemId] = {
        ...datasetItem,
        stale: value,
      };
    } else {
      updatedMap[datasetItemId] = datasetItem;
    }

    return updatedMap;
  }, {});

  return {
    ...outputMap,
    [promptId]: {
      datasetItemMap: updatedDatasetItemMap,
    },
  };
};

export type PlaygroundStore = {
  promptIds: string[];
  promptMap: Record<string, PlaygroundPromptType>;
  outputMap: PlaygroundOutputMap;
  datasetVariables: string[];
  providerValidationTrigger: number;
  selectedRuleIds: string[] | null;
  createdExperiments: LogExperiment[];
  isRunning: boolean;
  datasetFilters: Filters;
  datasetPage: number;
  datasetSize: number;
  progressTotal: number;
  progressCompleted: number;

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
  updateOutput: (
    promptId: string,
    datasetItemId: string,
    changes: Partial<PlaygroundOutput>,
  ) => void;
  updateOutputTraceId: (
    promptId: string,
    datasetItemId: string,
    traceId: string,
  ) => void;
  setDatasetVariables: (variables: string[]) => void;
  triggerProviderValidation: () => void;
  setSelectedRuleIds: (ruleIds: string[] | null) => void;
  setCreatedExperiments: (experiments: LogExperiment[]) => void;
  clearCreatedExperiments: () => void;
  setIsRunning: (isRunning: boolean) => void;
  setDatasetFilters: (filters: Filters) => void;
  setDatasetPage: (page: number) => void;
  setDatasetSize: (size: number) => void;
  resetDatasetFilters: () => void;
  setProgress: (completed: number, total: number) => void;
  resetProgress: () => void;
};

const usePlaygroundStore = create<PlaygroundStore>()(
  persist(
    (set) => ({
      promptIds: [],
      promptMap: {},
      outputMap: {},
      datasetVariables: [],
      providerValidationTrigger: 0,
      selectedRuleIds: null,
      createdExperiments: [],
      isRunning: false,
      datasetFilters: [],
      datasetPage: 1,
      datasetSize: 100,
      progressTotal: 0,
      progressCompleted: 0,

      updatePrompt: (promptId, changes) => {
        set((state) => {
          const newPromptMap = {
            ...state.promptMap,
            [promptId]: {
              ...state.promptMap[promptId],
              ...changes,
            },
          };

          return {
            ...state,
            promptMap: newPromptMap,
            outputMap: updateAllStaleStatusesForPromptOutput(
              promptId,
              state.outputMap,
              true,
            ),
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
      updateOutput: (
        promptId,
        datasetItemId,
        changes: Partial<PlaygroundOutput>,
      ) => {
        set((state) => {
          const key = datasetItemId
            ? [promptId, "datasetItemMap", datasetItemId]
            : [promptId];

          const output = get(state.outputMap, key);
          const newOutput = { ...output, stale: false, ...changes };
          const newOutputMap = { ...state.outputMap };

          lodashSet(newOutputMap, key, newOutput);

          return {
            ...state,
            outputMap: newOutputMap,
          };
        });
      },
      updateOutputTraceId: (promptId, datasetItemId, traceId) => {
        set((state) => {
          const key = datasetItemId
            ? [promptId, "datasetItemMap", datasetItemId]
            : [promptId];

          const output = get(state.outputMap, key);
          if (!output) return state;

          const newOutput = { ...output, traceId };
          const newOutputMap = { ...state.outputMap };

          lodashSet(newOutputMap, key, newOutput);

          return {
            ...state,
            outputMap: newOutputMap,
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
          };
        });
      },
      setIsRunning: (isRunning) => {
        set((state) => {
          return {
            ...state,
            isRunning,
          };
        });
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
      resetProgress: () => {
        set((state) => {
          return {
            ...state,
            progressCompleted: 0,
            progressTotal: 0,
          };
        });
      },
    }),
    {
      name: "PLAYGROUND_STATE",
    },
  ),
);

export const useOutputByPromptDatasetItemId = (
  promptId: string,
  datasetItemId?: string,
) =>
  usePlaygroundStore((state) => {
    const outputMapEntry = state.outputMap?.[promptId];

    if (
      outputMapEntry &&
      datasetItemId &&
      isPlaygroundOutputWithDatasetItem(outputMapEntry)
    ) {
      return outputMapEntry.datasetItemMap?.[datasetItemId] ?? null;
    }

    if (outputMapEntry && !isPlaygroundOutputWithDatasetItem(outputMapEntry)) {
      return outputMapEntry;
    }

    return null;
  });

export const useOutputValueByPromptDatasetItemId = (
  promptId: string,
  datasetItemId?: string,
) => {
  return useOutputByPromptDatasetItemId(promptId, datasetItemId)?.value ?? null;
};

export const useOutputLoadingByPromptDatasetItemId = (
  promptId: string,
  datasetItemId?: string,
) => {
  return (
    useOutputByPromptDatasetItemId(promptId, datasetItemId)?.isLoading ?? false
  );
};

export const useOutputStaleStatusByPromptDatasetItemId = (
  promptId: string,
  datasetItemId?: string,
) => {
  return (
    useOutputByPromptDatasetItemId(promptId, datasetItemId)?.stale ?? false
  );
};

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

export const useTraceIdByPromptDatasetItemId = (
  promptId: string,
  datasetItemId?: string,
) => {
  return (
    useOutputByPromptDatasetItemId(promptId, datasetItemId)?.traceId ?? null
  );
};

export const useSelectedRuleIdsByPromptDatasetItemId = (
  promptId: string,
  datasetItemId?: string,
): string[] | null | undefined => {
  return useOutputByPromptDatasetItemId(promptId, datasetItemId)
    ?.selectedRuleIds;
};

export const useDatasetVariables = () =>
  usePlaygroundStore((state) => state.datasetVariables);

export const useSetDatasetVariables = () =>
  usePlaygroundStore((state) => state.setDatasetVariables);

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

export const useClearCreatedExperiments = () =>
  usePlaygroundStore((state) => state.clearCreatedExperiments);

export const useIsRunning = () =>
  usePlaygroundStore((state) => state.isRunning);

export const useSetIsRunning = () =>
  usePlaygroundStore((state) => state.setIsRunning);

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

export const useResetProgress = () =>
  usePlaygroundStore((state) => state.resetProgress);

export default usePlaygroundStore;
