import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { DATASET_TYPE } from "@/types/datasets";
import { TooltipProvider } from "@/ui/tooltip";
import PlaygroundOutputCell from "./PlaygroundOutputCell";

const PROMPT_ID = "prompt-1";
const DATA_ITEM_ID = "item-1";

type Output = {
  isLoading: boolean;
  value: string | null;
  error?: string;
  stale: boolean;
  traceId?: string;
  selectedRuleIds?: string[] | null;
};

let output: Output;

vi.mock("@/store/PlaygroundStore", () => ({
  useOutputByPromptDatasetItemId: () => output,
  useDatasetType: () => DATASET_TYPE.DATASET,
  useExperimentIdByPromptId: () => undefined,
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
  useActiveProjectId: () => "project-1",
}));

vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: "dataset-1" }),
}));

vi.mock("@/shared/MarkdownPreview/MarkdownPreview", () => ({
  default: ({ children }: { children: string | null }) => (
    <div data-testid="markdown">{children}</div>
  ),
}));

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundOutputScoresContainer",
  () => ({
    default: () => <div data-testid="metric-chips" />,
  }),
);

const makeContext = () =>
  ({
    row: { original: { dataItemId: DATA_ITEM_ID } },
    column: {
      columnDef: { meta: { custom: { promptId: PROMPT_ID, promptIndex: 0 } } },
    },
    table: { options: { meta: {} } },
  }) as unknown as CellContext<{ dataItemId: string }, unknown>;

// The trace-link button (rendered once there is a traceId) sits in a Tooltip.
const renderCell = () =>
  render(
    <TooltipProvider>
      <PlaygroundOutputCell {...makeContext()} />
    </TooltipProvider>,
  );

beforeEach(() => {
  output = { isLoading: false, value: null, stale: false };
});

describe("PlaygroundOutputCell", () => {
  describe("a run that failed", () => {
    beforeEach(() => {
      output = {
        isLoading: false,
        value: null,
        error: "ratings not defined",
        stale: false,
      };
    });

    it("should show the failure as an error rather than as the model's answer", () => {
      renderCell();

      expect(screen.getByTestId("playground-output-error")).toHaveTextContent(
        "Run failed: ratings not defined",
      );
      expect(screen.queryByTestId("markdown")).not.toBeInTheDocument();
    });

    it("should offer no metric chips, since nothing will ever score the row", () => {
      renderCell();

      expect(screen.queryByTestId("metric-chips")).not.toBeInTheDocument();
    });
  });

  describe("a run that succeeded", () => {
    beforeEach(() => {
      output = {
        isLoading: false,
        value: "the answer",
        stale: false,
        traceId: "trace-1",
        selectedRuleIds: ["rule-1"],
      };
    });

    it("should render the output as before", () => {
      renderCell();

      expect(screen.getByTestId("markdown")).toHaveTextContent("the answer");
      expect(
        screen.queryByTestId("playground-output-error"),
      ).not.toBeInTheDocument();
    });

    it("should keep its metric chips", () => {
      renderCell();

      expect(screen.getByTestId("metric-chips")).toBeInTheDocument();
    });
  });
});
