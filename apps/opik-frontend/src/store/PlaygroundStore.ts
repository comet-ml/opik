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
}

interface PlaygroundOutputMap {
  [promptId: string]:
    | PlaygroundOutput
    | {
        datasetItemMap: {
          [datasetItemId: string]: PlaygroundOutput;
        };
      };
}

export type PlaygroundStore = {
  promptIds: string[];
  promptMap: Record<string, PlaygroundPromptType>;
  outputMap: PlaygroundOutputMap;

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
};

const usePlaygroundStore = create<PlaygroundStore>()(
  persist(
    (set) => ({
      promptIds: [],
      promptMap: {},
      outputMap: {},

      updatePrompt: (promptId, changes) => {
        set((state) => {
          return {
            ...state,
            promptMap: {
              ...state.promptMap,
              [promptId]: {
                ...state.promptMap[promptId],
                ...changes,
              },
            },
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
          const newOutput = { ...output, ...changes };
          const newOutputMap = { ...state.outputMap };

          lodashSet(newOutputMap, key, newOutput);

          return {
            ...state,
            outputMap: newOutputMap,
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

    if (outputMapEntry && datasetItemId && "datasetItemMap" in outputMapEntry) {
      return outputMapEntry.datasetItemMap?.[datasetItemId] ?? null;
    }

    if (outputMapEntry && !("datasetItemMap" in outputMapEntry)) {
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

export default usePlaygroundStore;
