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

let datasetType: DATASET_TYPE | null = DATASET_TYPE.DATASET;

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptMap: () => ({ [prompt.id]: prompt }),
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

const renderHeader = (versionItemsTotal: number | undefined) =>
  render(
    <TooltipProvider delayDuration={0}>
      <PlaygroundHeader
        workspaceName="default"
        datasetId="dataset-1::version-1"
        datasetName="My dataset"
        versionName="v1"
        versionItemsTotal={versionItemsTotal}
        onChangeDatasetId={vi.fn()}
        onReset={vi.fn()}
        onRunAll={onRunAll}
        onStopAll={vi.fn()}
      />
    </TooltipProvider>,
  );

const getRunButton = () => screen.getByTestId("playground-run-button");

// Radix opens tooltips on pointer move, not on focus, for a disabled trigger.
const hoverRunButton = () =>
  fireEvent.pointerMove(getRunButton(), { pointerType: "mouse" });

const pressRunShortcut = () =>
  fireEvent.keyDown(window, { key: "Enter", shiftKey: true });

beforeEach(() => {
  onRunAll.mockClear();
  datasetType = DATASET_TYPE.DATASET;
});

describe("PlaygroundHeader", () => {
  describe("a dataset version with no items", () => {
    it("should disable Run and explain why", async () => {
      renderHeader(0);
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "This dataset is empty. Add items to run an experiment",
      );
    });

    it("should name a test suite in the reason", async () => {
      datasetType = DATASET_TYPE.TEST_SUITE;

      renderHeader(0);
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "This test suite is empty. Add items to run an experiment",
      );
    });

    it("should not run from the keyboard shortcut", () => {
      renderHeader(0);
      pressRunShortcut();

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
  });

  it("should keep Run enabled while the item count is not known yet", () => {
    renderHeader(undefined);

    expect(getRunButton()).toBeEnabled();
  });
});
