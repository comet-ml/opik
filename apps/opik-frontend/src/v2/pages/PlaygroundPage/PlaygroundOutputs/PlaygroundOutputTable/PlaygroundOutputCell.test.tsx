import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { DATASET_TYPE } from "@/types/datasets";
import { TooltipProvider } from "@/ui/tooltip";
import PlaygroundOutputCell from "./PlaygroundOutputCell";

const PROMPT_ID = "prompt-1";
const DATA_ITEM_ID = "item-1";
const STALE_NOTE_TOOLTIP =
  "Prompt changed since the last run. Re-run to update results.";

type Output = {
  isLoading: boolean;
  value: string | null;
  error?: string;
  stale: boolean;
  traceId?: string;
  selectedRuleIds?: string[] | null;
};

let output: Output;
let datasetType: DATASET_TYPE;
let experimentId: string | undefined;

vi.mock("@/store/PlaygroundStore", () => ({
  useOutputByPromptDatasetItemId: () => output,
  useDatasetType: () => datasetType,
  useExperimentIdByPromptId: () => experimentId,
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
  useActiveProjectId: () => "project-1",
}));

vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: "dataset-1" }),
}));

vi.mock("@/shared/MarkdownPreview/MarkdownPreview", () => ({
  default: ({
    children,
    className,
  }: {
    children: string | null;
    className?: string;
  }) => (
    <div data-testid="markdown" className={className}>
      {children}
    </div>
  ),
}));

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundOutputScoresContainer",
  () => ({
    default: () => <div data-testid="metric-chips" />,
  }),
);

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundOutputAssertionStatus",
  () => ({
    default: () => <div data-testid="assertion-status" />,
  }),
);

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundTestSuiteLastRunOutput",
  () => ({
    default: () => <div data-testid="test-suite-output" />,
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
    <TooltipProvider delayDuration={0}>
      <PlaygroundOutputCell {...makeContext()} />
    </TooltipProvider>,
  );

const queryStaleNote = () =>
  screen.queryByTestId("playground-stale-output-note");

beforeEach(() => {
  output = { isLoading: false, value: null, stale: false };
  datasetType = DATASET_TYPE.DATASET;
  experimentId = undefined;
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

    it("should not say the prompt changed", () => {
      renderCell();

      expect(queryStaleNote()).not.toBeInTheDocument();
    });
  });

  describe("a run from before the prompt was edited", () => {
    it("should keep the output and its metric chips, dimmed, with a short note", () => {
      output = {
        isLoading: false,
        value: "the answer",
        stale: true,
        traceId: "trace-1",
        selectedRuleIds: ["rule-1"],
      };

      renderCell();

      const markdown = screen.getByTestId("markdown");
      expect(markdown).toHaveTextContent("the answer");
      expect(markdown).toHaveClass("text-muted-gray");
      expect(screen.getByTestId("metric-chips")).toBeInTheDocument();
      expect(queryStaleNote()).toHaveTextContent("Prompt changed");
      expect(screen.queryByText("No runs yet")).not.toBeInTheDocument();
    });

    it("should explain the note in a tooltip", async () => {
      output = { isLoading: false, value: "the answer", stale: true };

      renderCell();
      fireEvent.pointerMove(
        screen.getByTestId("playground-stale-output-note"),
        {
          pointerType: "mouse",
        },
      );

      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        STALE_NOTE_TOOLTIP,
      );
    });

    it("should keep a failed run's error readable, dimmed, with the note", () => {
      output = {
        isLoading: false,
        value: null,
        error: "ratings not defined",
        stale: true,
      };

      renderCell();

      const tag = screen.getByTestId("playground-output-error");
      expect(tag).toHaveTextContent("Run failed: ratings not defined");
      expect(tag).toHaveClass("opacity-50");
      expect(queryStaleNote()).toHaveTextContent("Prompt changed");
    });

    it("should still show No runs yet for a test suite", () => {
      datasetType = DATASET_TYPE.TEST_SUITE;
      experimentId = "experiment-1";
      output = { isLoading: false, value: null, stale: true };

      renderCell();

      expect(screen.getByText("No runs yet")).toBeInTheDocument();
      expect(screen.queryByTestId("assertion-status")).not.toBeInTheDocument();
    });
  });
});
