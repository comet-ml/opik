import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import useLoadPlayground from "./useLoadPlayground";
import usePlaygroundStore from "@/store/PlaygroundStore";
import { PLAYGROUND_SELECTED_DATASET_VERSION_KEY } from "@/constants/llm";
import { LogExperiment } from "@/types/playground";

const PROJECT_A = "project-a";
const PROJECT_B = "project-b";

const app = vi.hoisted(() => ({ activeProjectId: "" }));

vi.mock("@tanstack/react-router", () => ({ useNavigate: () => vi.fn() }));
vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "workspace" }),
  useActiveProjectId: () => app.activeProjectId,
}));
vi.mock("@/hooks/useLastPickedModel", () => ({ default: () => ["", vi.fn()] }));
vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: () => "",
    calculateDefaultModel: () => "",
    isDropdownModel: () => false,
  }),
}));
vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({ data: undefined, isPending: false }),
}));

const PROJECT_A_SESSION = {
  selectedRuleIds: ["rule-1"],
  createdExperiments: [{ id: "experiment-1" } as LogExperiment],
  experimentByPromptId: { "prompt-1": "experiment-1" },
  datasetVariables: ["question"],
  datasetFilters: [],
  datasetPage: 3,
  datasetSize: 50,
};

const CLEARED_SESSION = {
  selectedRuleIds: null,
  createdExperiments: [],
  experimentByPromptId: {},
  datasetVariables: [],
  datasetFilters: [],
  datasetPage: 1,
  datasetSize: 100,
};

const session = () => {
  const state = usePlaygroundStore.getState();
  return {
    selectedRuleIds: state.selectedRuleIds,
    createdExperiments: state.createdExperiments,
    experimentByPromptId: state.experimentByPromptId,
    datasetVariables: state.datasetVariables,
    datasetFilters: state.datasetFilters,
    datasetPage: state.datasetPage,
    datasetSize: state.datasetSize,
  };
};

const storedDatasetKey = () =>
  JSON.parse(
    localStorage.getItem(PLAYGROUND_SELECTED_DATASET_VERSION_KEY) ?? "null",
  );

const loadPrompt = (content: string) => {
  const { result } = renderHook(() => useLoadPlayground());
  act(() => {
    result.current.loadPlayground({ promptContent: content });
  });
};

const loadedContents = () => {
  const { promptIds, promptMap } = usePlaygroundStore.getState();
  return promptIds.map((id) => promptMap[id].messages.map((m) => m.content));
};

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    JSON.stringify("dataset-a::version-a"),
  );
  usePlaygroundStore.setState({
    lastActiveProjectId: PROJECT_A,
    promptIds: [],
    promptMap: {},
    ...PROJECT_A_SESSION,
  });
});

describe("useLoadPlayground", () => {
  it("clears the last project's dataset, rules and experiments when it loads into another project", () => {
    app.activeProjectId = PROJECT_B;

    loadPrompt("Hello");

    expect(loadedContents()).toEqual([["Hello"]]);
    expect(session()).toEqual(CLEARED_SESSION);
    expect(storedDatasetKey()).toBeNull();
    expect(usePlaygroundStore.getState().lastActiveProjectId).toBe(PROJECT_B);
  });

  it("keeps the project's dataset, rules and experiments when it loads into the same project", () => {
    app.activeProjectId = PROJECT_A;

    loadPrompt("Hello");

    expect(loadedContents()).toEqual([["Hello"]]);
    expect(session()).toEqual(PROJECT_A_SESSION);
    expect(storedDatasetKey()).toBe("dataset-a::version-a");
  });
});
