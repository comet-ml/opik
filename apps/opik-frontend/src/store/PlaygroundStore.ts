import { create } from "zustand";
import { createJSONStorage, persist, PersistStorage } from "zustand/middleware";
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
import { restoreMissingConfigKeys, RunFailureHint } from "@/lib/playground";
import { buildExperimentName } from "@/lib/experiments";
import { JsonObject } from "@/types/shared";
import { Filters } from "@/types/filters";
import { DATASET_TYPE } from "@/types/datasets";
import isEmpty from "lodash/isEmpty";
import isUndefined from "lodash/isUndefined";
import isArray from "lodash/isArray";
import isPlainObject from "lodash/isPlainObject";
import isString from "lodash/isString";
import get from "lodash/get";

interface PlaygroundOutput {
  isLoading: boolean;
  value: string | null;
  error?: string;
  errorHint?: RunFailureHint;
  stopped?: boolean;
  stale: boolean;
  staleChanges?: PlaygroundRunInputChange[];
  traceId?: string;
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

// Prompts stored before appliedChatPromptVersionId existed show their pinned
// version's messages, maybe edited, and without the marker the loader would
// apply the template over them once. A prompt that follows the latest version
// is left alone, since which version it applied is unknown.
const markStoredPinnedChatPromptApplied = (
  prompt: PlaygroundPromptType,
): PlaygroundPromptType => {
  if (
    !prompt ||
    typeof prompt !== "object" ||
    !prompt.loadedChatPromptId ||
    !prompt.loadedChatPromptVersionId ||
    prompt.appliedChatPromptVersionId ||
    !Array.isArray(prompt.messages) ||
    !prompt.messages.some((message) => !isEmpty(message?.content))
  ) {
    return prompt;
  }

  return {
    ...prompt,
    appliedChatPromptVersionId: prompt.loadedChatPromptVersionId,
  };
};

const MEDIA_PART_TYPES = new Set(["image_url", "video_url", "audio_url"]);

// A media part keeps its payload under a key named after its type, and the
// readers go straight to part[type].url.
const isUsablePart = (part: unknown) => {
  if (!isPlainObject(part)) return false;

  const { type } = part as { type?: unknown };

  return isString(type) && MEDIA_PART_TYPES.has(type)
    ? isPlainObject(get(part, type))
    : true;
};

const isUsableMessage = (message: unknown) => {
  if (!isPlainObject(message)) return false;

  const { content } = message as LLMMessage;

  return isString(content) || (isArray(content) && content.every(isUsablePart));
};

const isRenderablePrompt = (prompt: unknown) =>
  isPlainObject(prompt) &&
  isArray((prompt as PlaygroundPromptType).messages) &&
  (prompt as PlaygroundPromptType).messages.every(isUsableMessage);

// The page reads every listed prompt and every prompt in the map, so a single
// saved entry it can't render crashed the playground on every load.
const keepRenderablePrompts = (promptIds: unknown, promptMap: unknown) => {
  const storedPromptMap: Record<string, unknown> = isPlainObject(promptMap)
    ? (promptMap as Record<string, unknown>)
    : {};
  const renderablePromptIds = (isArray(promptIds) ? promptIds : []).filter(
    (id): id is string =>
      isString(id) && isRenderablePrompt(storedPromptMap[id]),
  );

  return {
    promptIds: renderablePromptIds,
    promptMap: Object.fromEntries(
      renderablePromptIds.map((id) => {
        const prompt = storedPromptMap[id] as PlaygroundPromptType;
        return [
          id,
          isPlainObject(prompt.configs) ? prompt : { ...prompt, configs: {} },
        ];
      }),
    ) as Record<string, PlaygroundPromptType>,
  };
};

let hasWarnedAboutFailedSave = false;

const warnOnceAboutFailedSave = (message: string, error: unknown) => {
  if (hasWarnedAboutFailedSave) return;
  hasWarnedAboutFailedSave = true;
  console.warn(message, error);
};

// zustand saves after every update and doesn't catch a failed write, so with a
// full localStorage every store action threw, and one called from an effect
// took the whole page down. The update is already in memory by then.
const createPlaygroundStorage = <
  S extends { outputMap: PlaygroundOutputMap },
>(): PersistStorage<S> | undefined => {
  const storage = createJSONStorage<S>(() => localStorage);

  if (!storage) return storage;

  return {
    ...storage,
    setItem: (name, value) => {
      try {
        storage.setItem(name, value);
      } catch (error) {
        // Outputs are most of what is saved, and the prompts matter more.
        try {
          storage.setItem(name, {
            ...value,
            state: { ...value.state, outputMap: {} },
          });
          warnOnceAboutFailedSave(
            "The playground outputs don't fit in this browser's storage, so only the prompts are saved.",
            error,
          );
        } catch {
          warnOnceAboutFailedSave(
            "The playground can't be saved in this browser's storage. Changes stay until the page is reloaded.",
            error,
          );
        }
      }
    },
  };
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
  // Sets rather than arrays so there is one way to say "nothing" — an empty array beside null is
  // what let an empty stop queue read as a stop still pending. Safe to hold a Set only because
  // partialize strips both: neither survives JSON, and neither should outlive the tab.
  watchLapsed: Set<string>;
  stopsAwaitingRun: Set<string>;
  cancelsInFlight: Set<string>;
  experimentName: string | null;
  lastSuggestedExperimentName: string | null;
  datasetType: DATASET_TYPE | null;
  experimentByPromptId: Record<string, string>;
  runTotalItemsByPromptId: Record<string, number>;
  datasetItemsTotal: number | null;
  hasUnseenRunCompletion: boolean;
  isRunInFlight: boolean;
  /** True from mount until a reloaded page has learned whether its run is still going. */
  isResumingRun: boolean;
  scoresByDatasetId: Record<string, string[] | null>;
  appliedChatPromptVersionsBackfilled: boolean;

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
  clearPromptOutputStale: (promptId: string) => void;
  updateOutput: (promptId: string, changes: Partial<PlaygroundOutput>) => void;
  updateOutputTraceId: (promptId: string, traceId: string) => void;
  setDatasetVariables: (variables: string[]) => void;
  setDatasetSampleData: (data: JsonObject | null) => void;
  triggerProviderValidation: () => void;
  setSelectedRuleIds: (ruleIds: string[] | null) => void;
  setCreatedExperiments: (experiments: LogExperiment[]) => void;
  setRunTotalItemsForPrompts: (promptIds: string[], total: number) => void;
  setDatasetItemsTotal: (total: number | null) => void;
  setHasUnseenRunCompletion: (value: boolean) => void;
  setIsRunInFlight: (value: boolean) => void;
  clearCreatedExperiments: () => void;
  setIsRunning: (isRunning: boolean) => void;
  setPromptRunning: (promptId: string, running: boolean) => void;
  setAllRunning: (running: boolean) => void;
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
  markWatchLapsed: (promptIds: string[]) => void;
  clearWatchLapsed: (promptIds: string[]) => void;
  clearAllWatchLapsed: () => void;
  queueStopAwaitingRun: (promptIds: string[]) => void;
  clearStopAwaitingRun: (promptIds: string[]) => void;
  clearAllStopsAwaitingRun: () => void;
  markCancelInFlight: (promptIds: string[]) => void;
  clearCancelInFlight: (promptIds: string[]) => void;
  setLastActiveProjectId: (projectId: string | null) => void;
  setDatasetType: (type: DATASET_TYPE | null) => void;
  setExperimentByPromptId: (map: Record<string, string>) => void;
  forgetExperimentForPrompt: (promptId: string) => void;
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
      watchLapsed: new Set<string>(),
      stopsAwaitingRun: new Set<string>(),
      cancelsInFlight: new Set<string>(),
      experimentName: null,
      lastSuggestedExperimentName: null,
      datasetType: null,
      experimentByPromptId: {},
      runTotalItemsByPromptId: {},
      datasetItemsTotal: null,
      hasUnseenRunCompletion: false,
      isRunInFlight: false,
      isResumingRun: false,
      scoresByDatasetId: {},
      appliedChatPromptVersionsBackfilled: true,

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
      clearPromptOutputStale: (promptId) => {
        set((state) => {
          const output = state.outputMap[promptId];
          if (!output?.stale) return state;

          return {
            ...state,
            outputMap: {
              ...state.outputMap,
              [promptId]: { ...output, stale: false, staleChanges: undefined },
            },
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
      setRunTotalItemsForPrompts: (promptIds, total) => {
        set((state) => ({
          ...state,
          runTotalItemsByPromptId: {
            ...state.runTotalItemsByPromptId,
            ...Object.fromEntries(
              promptIds.map((promptId) => [promptId, total]),
            ),
          },
        }));
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
            runTotalItemsByPromptId: {},
          };
        });
      },
      setIsRunning: (isRunning) => {
        set((state) => ({ ...state, isRunning }));
      },
      setPromptRunning: (promptId, running) => {
        set((state) => {
          const isRunningMap = { ...state.isRunningMap };
          if (running) {
            isRunningMap[promptId] = true;
          } else {
            delete isRunningMap[promptId];
          }
          return { ...state, isRunningMap };
        });
      },
      setAllRunning: (running) => {
        set((state) => ({
          ...state,
          isRunningMap: running
            ? Object.fromEntries(state.promptIds.map((id) => [id, true]))
            : {},
        }));
      },
      settleRun: () => {
        set((state) => ({
          ...state,
          isRunningMap: {},
          isRunInFlight: false,
          isResumingRun: false,
          watchLapsed: new Set<string>(),
          stopsAwaitingRun: new Set<string>(),
          cancelsInFlight: new Set<string>(),
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
      markWatchLapsed: (promptIds) => {
        set((state) => ({
          ...state,
          watchLapsed: new Set([...state.watchLapsed, ...promptIds]),
        }));
      },
      clearWatchLapsed: (promptIds) => {
        set((state) => {
          const watchLapsed = new Set(state.watchLapsed);
          promptIds.forEach((promptId) => watchLapsed.delete(promptId));
          return { ...state, watchLapsed };
        });
      },
      clearAllWatchLapsed: () => {
        set((state) => ({ ...state, watchLapsed: new Set<string>() }));
      },
      queueStopAwaitingRun: (promptIds) => {
        set((state) => ({
          ...state,
          stopsAwaitingRun: new Set([...state.stopsAwaitingRun, ...promptIds]),
        }));
      },
      clearStopAwaitingRun: (promptIds) => {
        set((state) => {
          const stopsAwaitingRun = new Set(state.stopsAwaitingRun);
          promptIds.forEach((promptId) => stopsAwaitingRun.delete(promptId));
          return { ...state, stopsAwaitingRun };
        });
      },
      clearAllStopsAwaitingRun: () => {
        set((state) => ({ ...state, stopsAwaitingRun: new Set<string>() }));
      },
      markCancelInFlight: (promptIds) => {
        set((state) => ({
          ...state,
          cancelsInFlight: new Set([...state.cancelsInFlight, ...promptIds]),
        }));
      },
      clearCancelInFlight: (promptIds) => {
        set((state) => {
          const cancelsInFlight = new Set(state.cancelsInFlight);
          promptIds.forEach((promptId) => cancelsInFlight.delete(promptId));
          return { ...state, cancelsInFlight };
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
      forgetExperimentForPrompt: (promptId) => {
        set((state) => ({
          ...state,
          experimentByPromptId: Object.fromEntries(
            Object.entries(state.experimentByPromptId).filter(
              ([id]) => id !== promptId,
            ),
          ),
        }));
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
      storage: createPlaygroundStorage(),
      // Normalizes on every load rather than through `migrate`: persisted blobs carry no version
      // (this store never set one), and zustand only migrates a blob whose stored version is a
      // number — so a migrate hook would skip exactly the states that need repairing. Cheap and
      // idempotent, since restoreMissingConfigKeys returns the prompt untouched when it is complete.
      merge: (persisted, current) => {
        const stored = persisted as Partial<PlaygroundStore> | undefined;
        const state = {
          ...current,
          ...stored,
        };
        const { promptIds, promptMap } = keepRenderablePrompts(
          state.promptIds,
          state.promptMap,
        );

        // Only state written before appliedChatPromptVersionId existed is backfilled: current
        // state can hold a version picked a moment ago whose messages have not loaded yet, and
        // marking it would keep the old messages under the new version. A stored flag rather
        // than persist `version`, because an older build that finds a newer version drops the
        // whole stored state.
        const backfillAppliedVersions =
          !stored?.appliedChatPromptVersionsBackfilled;

        return {
          ...state,
          promptIds,
          outputMap: pick(state.outputMap, promptIds),
          appliedChatPromptVersionsBackfilled: true,
          promptMap: mapValues(promptMap, (prompt) => {
            const restored = restoreMissingConfigKeys(prompt);
            return backfillAppliedVersions
              ? markStoredPinnedChatPromptApplied(restored)
              : restored;
          }),
        };
      },
      partialize: (state) => {
        /* eslint-disable @typescript-eslint/no-unused-vars */
        const {
          datasetSampleData,
          progressTotal,
          progressCompleted,
          datasetItemsTotal,
          isRunning,
          isRunningMap,
          isResumingRun,
          watchLapsed,
          stopsAwaitingRun,
          cancelsInFlight,
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

export const useClearPromptOutputStale = () =>
  usePlaygroundStore((state) => state.clearPromptOutputStale);

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

export const useSetRunTotalItemsForPrompts = () =>
  usePlaygroundStore((state) => state.setRunTotalItemsForPrompts);

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

export const getRunningPromptIds = () =>
  Object.keys(usePlaygroundStore.getState().isRunningMap);

export const useSetPromptRunning = () =>
  usePlaygroundStore((state) => state.setPromptRunning);

export const useSetAllRunning = () =>
  usePlaygroundStore((state) => state.setAllRunning);

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

export const useShowsRunProgress = () =>
  usePlaygroundStore(
    (state) => state.progressTotal > 0 || state.progressPhase !== null,
  );

export const useHasRunProgress = () =>
  usePlaygroundStore((state) => state.progressTotal > 0);

export const useProgressCompleted = () =>
  usePlaygroundStore((state) => state.progressCompleted);

export const useSetProgress = () =>
  usePlaygroundStore((state) => state.setProgress);

export const useProgressPhase = () =>
  usePlaygroundStore((state) => state.progressPhase);

export const useSetProgressPhase = () =>
  usePlaygroundStore((state) => state.setProgressPhase);

export const useIsAnyWatchLapsed = () =>
  usePlaygroundStore((state) => state.watchLapsed.size > 0);

export const useClearAllWatchLapsed = () =>
  usePlaygroundStore((state) => state.clearAllWatchLapsed);

export const useMarkWatchLapsed = () =>
  usePlaygroundStore((state) => state.markWatchLapsed);

export const useClearWatchLapsed = () =>
  usePlaygroundStore((state) => state.clearWatchLapsed);

export const useIsWatchLapsed = (promptId: string) =>
  usePlaygroundStore((state) => state.watchLapsed.has(promptId));

export const useClearAllStopsAwaitingRun = () =>
  usePlaygroundStore((state) => state.clearAllStopsAwaitingRun);

export const useQueueStopAwaitingRun = () =>
  usePlaygroundStore((state) => state.queueStopAwaitingRun);

export const useClearStopAwaitingRun = () =>
  usePlaygroundStore((state) => state.clearStopAwaitingRun);

export const useForgetExperimentForPrompt = () =>
  usePlaygroundStore((state) => state.forgetExperimentForPrompt);

/**
 * Both waits read as Stopping to the user: `stopsAwaitingRun` is waiting for the run to be named,
 * `cancelsInFlight` for the cancel to answer. They are kept apart because only the first may be
 * replayed against a later launch — replaying the second would cancel a run that just started.
 */
export const useIsStopping = (promptId: string) =>
  usePlaygroundStore(
    (state) =>
      state.stopsAwaitingRun.has(promptId) ||
      state.cancelsInFlight.has(promptId),
  );

export const useIsStoppingAll = () =>
  usePlaygroundStore((state) => {
    if (state.stopsAwaitingRun.size === 0 && state.cancelsInFlight.size === 0) {
      return false;
    }
    return Object.keys(state.isRunningMap).every(
      (promptId) =>
        state.stopsAwaitingRun.has(promptId) ||
        state.cancelsInFlight.has(promptId),
    );
  });

export const useMarkCancelInFlight = () =>
  usePlaygroundStore((state) => state.markCancelInFlight);

export const useClearCancelInFlight = () =>
  usePlaygroundStore((state) => state.clearCancelInFlight);

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
