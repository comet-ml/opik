import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { DATASET_TYPE } from "@/types/datasets";
import { PlaygroundRunInputChange } from "@/types/playground";
import { PlaygroundExperimentItem } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/usePlaygroundExperimentItem";
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
  staleChanges?: PlaygroundRunInputChange[];
  traceId?: string;
  selectedRuleIds?: string[] | null;
};

let output: Output;
let datasetType: DATASET_TYPE;
let experimentId: string | undefined;
let experimentItem: PlaygroundExperimentItem;

vi.mock("@/store/PlaygroundStore", () => ({
  useOutputByPromptId: () => output,
  useDatasetType: () => datasetType,
  useExperimentIdByPromptId: () => experimentId,
}));

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/usePlaygroundExperimentItem",
  () => ({
    default: () => experimentItem,
  }),
);

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader",
  () => ({
    default: () => <div data-testid="output-loader" />,
  }),
);

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

const noExperimentItem: PlaygroundExperimentItem = {
  hasItem: false,
  notRun: false,
  cancelled: false,
  output: null,
  error: null,
  traceId: null,
  runCount: 0,
};

beforeEach(() => {
  output = { isLoading: false, value: null, stale: false };
  datasetType = DATASET_TYPE.DATASET;
  experimentId = undefined;
  experimentItem = noExperimentItem;
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

    it("should name what changed, with the full sentence in the tooltip", async () => {
      output = {
        isLoading: false,
        value: "the answer",
        stale: true,
        staleChanges: ["model"],
      };

      renderCell();
      const note = screen.getByTestId("playground-stale-output-note");
      expect(note).toHaveTextContent("Model changed");
      fireEvent.pointerMove(note, { pointerType: "mouse" });

      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "Model changed since the last run. Re-run to update results.",
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

  // A dataset run executes on the server, so nothing streams into the store: the cell's content,
  // its failure state and its trace link all come from the experiment item instead.
  describe("a run that executed on the backend", () => {
    beforeEach(() => {
      experimentId = "experiment-1";
    });

    it("should render the output that came back with the experiment item", () => {
      experimentItem = {
        hasItem: true,
        notRun: false,
        cancelled: false,
        output: "the server's answer",
        error: null,
        traceId: "trace-1",
        runCount: 1,
      };

      renderCell();

      expect(screen.getByTestId("markdown")).toHaveTextContent(
        "the server's answer",
      );
    });

    it("should show a row that failed as an error, not as an empty answer", () => {
      experimentItem = {
        hasItem: true,
        notRun: false,
        cancelled: false,
        output: null,
        error: "provider rejected the request",
        traceId: "trace-1",
        runCount: 1,
      };

      renderCell();

      expect(screen.getByTestId("playground-output-error")).toHaveTextContent(
        "provider rejected the request",
      );
      expect(screen.queryByTestId("markdown")).not.toBeInTheDocument();
    });

    it("should keep loading while the row has no experiment item yet", () => {
      renderCell();

      expect(screen.getByTestId("output-loader")).toBeInTheDocument();
      expect(screen.queryByTestId("markdown")).not.toBeInTheDocument();
    });

    // Stopping a run leaves the rows it never reached without an experiment item, and nothing
    // will ever write one. Keyed off the item alone, those rows load forever.
    it("should stop loading and say so for a row the stopped run never reached", () => {
      experimentItem = {
        hasItem: false,
        notRun: true,
        cancelled: true,
        output: null,
        error: null,
        traceId: null,
        runCount: 0,
      };

      renderCell();

      expect(screen.queryByTestId("output-loader")).not.toBeInTheDocument();
      expect(screen.getByText("Cancelled")).toBeInTheDocument();
    });

    it("should settle empty rather than load forever when the call returned nothing", () => {
      experimentItem = {
        hasItem: true,
        notRun: false,
        cancelled: false,
        output: null,
        error: null,
        traceId: "trace-1",
        runCount: 1,
      };

      renderCell();

      expect(screen.queryByTestId("output-loader")).not.toBeInTheDocument();
    });

    it("should offer the trace link using the item's trace", () => {
      experimentItem = {
        hasItem: true,
        notRun: false,
        cancelled: false,
        output: "the server's answer",
        error: null,
        traceId: "trace-1",
        runCount: 1,
      };

      renderCell();

      expect(screen.getByRole("button")).toBeInTheDocument();
    });

    it("should offer no trace link before the item carries a trace", () => {
      experimentItem = {
        hasItem: true,
        notRun: false,
        cancelled: false,
        output: "the server's answer",
        error: null,
        traceId: null,
        runCount: 1,
      };

      renderCell();

      expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });
  });
});
