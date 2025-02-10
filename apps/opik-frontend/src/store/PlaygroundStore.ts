import { create } from "zustand";
import { persist } from "zustand/middleware";
import pick from "lodash/pick";

import { PlaygroundPromptType } from "@/types/playground";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import lodashSet from "lodash/set";

interface PlaygroundOutput {
  isLoading: boolean;
  value: string | null;
  stale: boolean;
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
  setDatasetVariables: (variables: string[]) => void;
};

const usePlaygroundStore = create<PlaygroundStore>()(
  persist(
    (set) => ({
      promptIds: [],
      promptMap: {},
      outputMap: {},
      datasetVariables: [],

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
      setDatasetVariables: (variables) => {
        set((state) => {
          return {
            ...state,
            datasetVariables: variables,
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

export const useDatasetVariables = () =>
  usePlaygroundStore((state) => state.datasetVariables);

export const useSetDatasetVariables = () =>
  usePlaygroundStore((state) => state.setDatasetVariables);

export default usePlaygroundStore;
