import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { DATASET_TYPE } from "@/types/datasets";
import { PlaygroundExperimentItem } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/usePlaygroundExperimentItem";
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

let experimentId: string | undefined;
let experimentItem: PlaygroundExperimentItem;

vi.mock("@/store/PlaygroundStore", () => ({
  useOutputByPromptId: () => output,
  useDatasetType: () => DATASET_TYPE.DATASET,
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
    // will ever write one. Keyed off the item alone, those rows load for ever.
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

    it("should settle empty rather than load for ever when the call returned nothing", () => {
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
