import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { DATASET_TYPE } from "@/types/datasets";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import PlaygroundHeader from "./PlaygroundHeader";

const prompt: PlaygroundPromptType = {
  id: "prompt-1",
  name: "Prompt",
  model: PROVIDER_MODEL_TYPE.GPT_4,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  messages: [{ id: "message-1", role: LLM_MESSAGE_ROLE.user, content: "Hi" }],
};

const secondPrompt: PlaygroundPromptType = { ...prompt, id: "prompt-2" };

let promptMap: Record<string, PlaygroundPromptType> = { [prompt.id]: prompt };
let datasetType: DATASET_TYPE | null = DATASET_TYPE.DATASET;

let isStoppingAll = false;
let isResumingRun = false;
let storeState = {
  isRunningMap: {},
  stopsAwaitingRun: new Set<string>(),
  cancelsInFlight: new Set<string>(),
};

vi.mock("@/store/PlaygroundStore", () => ({
  default: { getState: () => storeState },
  usePromptMap: () => promptMap,
  useClearCreatedExperiments: () => vi.fn(),
  useCreatedExperiments: () => [],
  useIsRunning: () => false,
  useSetSelectedRuleIds: () => vi.fn(),
  useResetDatasetFilters: () => vi.fn(),
  useResetOutputMap: () => vi.fn(),
  useSetExperimentName: () => vi.fn(),
  useSetDatasetType: () => vi.fn(),
  useDatasetType: () => datasetType,
  useDatasetFilters: () => [],
  useSetDatasetFilters: () => vi.fn(),
  // Added with the server-side run: the header reads a run's state to decide what it may offer
  useIsStoppingAll: () => isStoppingAll,
  useIsResumingRun: () => isResumingRun,
  useDatasetItemsTotal: () => null,
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: {
      canViewExperiments: true,
      canCreateExperiments: true,
      canViewDatasets: true,
    },
  }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => null,
}));

vi.mock("@/api/datasets/useDatasetItemsList", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/api/datasets/useDatasetVersionsList", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/v2/pages/PlaygroundPage/RunExperimentControl", () => ({
  default: () => null,
}));

vi.mock("@/shared/FiltersButton/FiltersButton", () => ({
  default: () => null,
}));

vi.mock(
  "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton",
  () => ({ default: () => null }),
);

const onRunAll = vi.fn();
const onRunSingle = vi.fn();

const renderHeader = (versionItemsTotal: number | undefined) =>
  render(
    <TooltipProvider delayDuration={0}>
      <div data-prompt-id={secondPrompt.id}>
        <textarea data-testid="second-prompt-editor" />
      </div>
      <PlaygroundHeader
        workspaceName="default"
        datasetId="dataset-1::version-1"
        datasetName="My dataset"
        versionName="v1"
        versionItemsTotal={versionItemsTotal}
        onChangeDatasetId={vi.fn()}
        onReset={vi.fn()}
        onRunAll={onRunAll}
        onRunSingle={onRunSingle}
        onStopAll={vi.fn()}
      />
    </TooltipProvider>,
  );

const getRunButton = () => screen.getByTestId("playground-run-button");

// Radix opens tooltips on pointer move, not on focus, for a disabled trigger.
const hoverRunButton = () =>
  fireEvent.pointerMove(getRunButton(), { pointerType: "mouse" });

const pressRunShortcut = (target: Window | Element = window) =>
  fireEvent.keyDown(target, { key: "Enter", metaKey: true });

const pressRunShortcutInSecondPrompt = () =>
  pressRunShortcut(screen.getByTestId("second-prompt-editor"));

beforeEach(() => {
  isStoppingAll = false;
  isResumingRun = false;
  storeState = {
    isRunningMap: {},
    stopsAwaitingRun: new Set<string>(),
    cancelsInFlight: new Set<string>(),
  };
  onRunAll.mockClear();
  onRunSingle.mockClear();
  promptMap = { [prompt.id]: prompt };
  datasetType = DATASET_TYPE.DATASET;
});

describe("PlaygroundHeader", () => {
  describe("a dataset version with no items", () => {
    it("should disable Run and explain why", async () => {
      renderHeader(0);
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "This dataset version has no items. Add items or select another version to run an experiment",
      );
    });

    it("should name a test suite in the reason", async () => {
      datasetType = DATASET_TYPE.TEST_SUITE;

      renderHeader(0);
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "This test suite version has no items. Add items or select another version to run an experiment",
      );
    });

    it("should not run from the keyboard shortcut", () => {
      renderHeader(0);
      pressRunShortcut();

      expect(onRunAll).not.toHaveBeenCalled();
    });

    it("should not run the prompt the cursor is in from the keyboard shortcut", () => {
      promptMap = { [prompt.id]: prompt, [secondPrompt.id]: secondPrompt };

      renderHeader(0);
      pressRunShortcutInSecondPrompt();

      expect(onRunSingle).not.toHaveBeenCalled();
      expect(onRunAll).not.toHaveBeenCalled();
    });
  });

  describe("a dataset version with items", () => {
    it("should run from the button and the keyboard shortcut", () => {
      renderHeader(3);

      fireEvent.click(getRunButton());
      pressRunShortcut();

      expect(getRunButton()).toBeEnabled();
      expect(onRunAll).toHaveBeenCalledTimes(2);
    });

    it("should run only the prompt the cursor is in from the keyboard shortcut", () => {
      promptMap = { [prompt.id]: prompt, [secondPrompt.id]: secondPrompt };

      renderHeader(3);
      pressRunShortcutInSecondPrompt();

      expect(onRunSingle).toHaveBeenCalledWith(secondPrompt.id);
      expect(onRunAll).not.toHaveBeenCalled();
    });

    it("should not run from the keyboard shortcut while a run is being picked back up", () => {
      isResumingRun = true;
      promptMap = { [prompt.id]: prompt, [secondPrompt.id]: secondPrompt };

      renderHeader(3);
      pressRunShortcut();
      pressRunShortcutInSecondPrompt();

      expect(onRunAll).not.toHaveBeenCalled();
      expect(onRunSingle).not.toHaveBeenCalled();
    });

    it("should not run all from the keyboard shortcut while every run is stopping", () => {
      isStoppingAll = true;

      renderHeader(3);
      pressRunShortcut();

      expect(onRunAll).not.toHaveBeenCalled();
    });

    it("should not run the prompt the cursor is in while its run is stopping", () => {
      promptMap = { [prompt.id]: prompt, [secondPrompt.id]: secondPrompt };
      storeState.cancelsInFlight = new Set([secondPrompt.id]);

      renderHeader(3);
      pressRunShortcutInSecondPrompt();

      expect(onRunSingle).not.toHaveBeenCalled();
    });
  });

  it("should keep Run enabled while the item count is not known yet", () => {
    renderHeader(undefined);

    expect(getRunButton()).toBeEnabled();
  });
});
