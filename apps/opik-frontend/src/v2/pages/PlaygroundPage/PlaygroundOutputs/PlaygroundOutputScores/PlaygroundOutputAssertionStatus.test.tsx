import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import PlaygroundOutputAssertionStatus from "./PlaygroundOutputAssertionStatus";
import {
  ONLINE_SCORING_REASON,
  ONLINE_SCORING_STATE,
  OnlineScoringStatus,
} from "@/types/automations";

const EXPERIMENT_ID = "experiment-1";
const DATASET_ITEM_ID = "item-1";

type CompareData = {
  content: {
    experiment_items: {
      dataset_item_id: string;
      created_at: string;
      trace_id?: string;
      assertion_results?: unknown[];
    }[];
  }[];
};

let experimentStatus: string;
let compareData: CompareData | undefined;
let statusesByTrace: Record<string, OnlineScoringStatus[]>;
let suiteAssertionCount: number | undefined;

vi.mock("../suiteAssertions", () => ({
  useSuiteAssertionCount: () => suiteAssertionCount,
}));
let compareOptions: {
  refetchInterval: (query: { state: { data?: CompareData } }) => number | false;
};

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({ data: { status: experimentStatus } }),
  useQueries: ({
    queries,
    combine,
  }: {
    queries: { queryKey: [string, { traceId: string }] }[];
    combine: (results: unknown[]) => unknown;
  }) =>
    combine(
      queries.map(({ queryKey: [, { traceId }] }) => ({
        data: { content: statusesByTrace[traceId] ?? [] },
        dataUpdatedAt: Date.now(),
      })),
    ),
  useQueryClient: () => ({
    getQueryData: ([, { traceId }]: [string, { traceId: string }]) => ({
      content: statusesByTrace[traceId] ?? [],
    }),
  }),
}));

vi.mock("@/api/datasets/useCompareExperimentsList", () => ({
  default: (_params: unknown, options: typeof compareOptions) => {
    compareOptions = options;
    return { data: compareData };
  },
}));

vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({
    children,
    content,
  }: {
    children: React.ReactNode;
    content: string;
  }) => <span title={content}>{children}</span>,
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
}));

vi.mock("@/v2/pages-shared/experiments/TestSuiteExperiment/PassedCell", () => ({
  ERROR_RESULT: (reason: string) => ({
    status: "error",
    evaluating: false,
    reason,
  }),
  getStatusFromExperimentItems: (
    _row: unknown,
    finished: boolean,
    suiteHasAssertions: boolean,
  ) =>
    finished
      ? { status: "scoring-error", evaluating: false }
      : suiteHasAssertions
        ? { status: undefined, evaluating: true }
        : { status: "skipped", evaluating: false },
  StatusTag: ({
    status,
    evaluating,
    reason,
  }: {
    status?: string;
    evaluating: boolean;
    reason?: string;
  }) => (
    <div data-testid="status" title={reason}>
      {evaluating ? "evaluating" : status}
    </div>
  ),
}));

const minutesAgo = (minutes: number) =>
  new Date(Date.now() - minutes * 60_000).toISOString();

const compareWithItemWritten = (createdAt: string): CompareData => ({
  content: [
    {
      experiment_items: [
        {
          dataset_item_id: DATASET_ITEM_ID,
          created_at: createdAt,
          trace_id: "trace-1",
        },
      ],
    },
  ],
});

const renderStatus = () =>
  render(
    <PlaygroundOutputAssertionStatus
      experimentId={EXPERIMENT_ID}
      datasetItemId={DATASET_ITEM_ID}
      datasetId="dataset-1"
    />,
  );

const pollWith = (data: CompareData | undefined) =>
  compareOptions.refetchInterval({ state: { data } });

beforeEach(() => {
  experimentStatus = "running";
  compareData = undefined;
  statusesByTrace = {};
  suiteAssertionCount = 1;
});

const judgeStatus = (
  state: ONLINE_SCORING_STATE,
  suiteAssertion = true,
): OnlineScoringStatus => ({
  rule_id: "r1",
  suite_assertion: suiteAssertion,
  state,
  reason: ONLINE_SCORING_REASON.RATE_LIMITED,
  attempt: 2,
  max_attempts: 3,
  updated_at: new Date().toISOString(),
});

const compareWithRuns = (
  ...runs: { traceId: string; scored?: boolean }[]
): CompareData => ({
  content: [
    {
      experiment_items: runs.map(({ traceId, scored }) => ({
        dataset_item_id: DATASET_ITEM_ID,
        created_at: minutesAgo(1),
        trace_id: traceId,
        assertion_results: scored ? [{}] : [],
      })),
    },
  ],
});

describe("PlaygroundOutputAssertionStatus", () => {
  it("should count the wait from when the row's item was written, so a reload does not restart it", () => {
    compareData = compareWithItemWritten(minutesAgo(10));

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("scoring-error");
    // A judge whose provider hangs reports late, so the row is still looked at once a minute
    expect(pollWith(compareData)).toBe(60000);
  });

  it("should stop looking 45 minutes after the row's item was written", () => {
    compareData = compareWithItemWritten(minutesAgo(46));

    renderStatus();

    expect(pollWith(compareData)).toBe(false);
  });

  it("should keep evaluating a row whose item was written moments ago", () => {
    compareData = compareWithItemWritten(minutesAgo(1));

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("evaluating");
    expect(pollWith(compareData)).toBe(5000);
  });

  it("should keep polling until the row's item shows up", () => {
    renderStatus();

    expect(pollWith(undefined)).toBe(5000);
  });

  it("should say the judge is retrying instead of ending the wait with a scoring error", () => {
    statusesByTrace = {
      "trace-1": [judgeStatus(ONLINE_SCORING_STATE.RETRYING)],
    };
    compareData = compareWithItemWritten(minutesAgo(10));

    renderStatus();

    expect(screen.getByText("Scoring failed, retrying")).toBeInTheDocument();
    expect(
      screen.getByTitle(
        "The provider is rate limiting calls. Retrying (attempt 2 of 3), which can take several minutes.",
      ),
    ).toBeInTheDocument();
    expect(pollWith(compareData)).toBe(30000);
  });

  it("should show why the judge gave up without waiting for the run to end", () => {
    statusesByTrace = {
      "trace-1": [judgeStatus(ONLINE_SCORING_STATE.SKIPPED)],
    };
    compareData = compareWithItemWritten(minutesAgo(1));

    renderStatus();

    expect(screen.getByTestId("status")).toHaveAttribute(
      "title",
      "The provider is rate limiting calls. No score will be stored.",
    );
  });

  it("should ignore the statuses of project rules scoring the same trace", () => {
    statusesByTrace = {
      "trace-1": [judgeStatus(ONLINE_SCORING_STATE.SKIPPED, false)],
    };
    compareData = compareWithItemWritten(minutesAgo(1));

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("evaluating");
  });

  it("should keep evaluating while another run of the item may still get its result", () => {
    statusesByTrace = {
      "trace-1": [judgeStatus(ONLINE_SCORING_STATE.SKIPPED)],
    };
    compareData = compareWithRuns(
      { traceId: "trace-1" },
      { traceId: "trace-2" },
    );

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("evaluating");
  });

  it("should show why the judge gave up once every run without a result has given up", () => {
    statusesByTrace = {
      "trace-2": [judgeStatus(ONLINE_SCORING_STATE.SKIPPED)],
    };
    compareData = compareWithRuns(
      { traceId: "trace-1", scored: true },
      { traceId: "trace-2" },
    );

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("error");
  });

  it("should keep evaluating until the suite version is known", () => {
    suiteAssertionCount = undefined;
    compareData = compareWithItemWritten(minutesAgo(1));

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("evaluating");
  });

  it("should show a row as skipped when neither it nor the suite has assertions", () => {
    suiteAssertionCount = 0;
    compareData = compareWithItemWritten(minutesAgo(1));

    renderStatus();

    expect(screen.getByTestId("status")).toHaveTextContent("skipped");
  });

  it("should say the item is retrying while one of its judges retries, though another gave up", () => {
    statusesByTrace = {
      "trace-1": [
        judgeStatus(ONLINE_SCORING_STATE.SKIPPED),
        judgeStatus(ONLINE_SCORING_STATE.RETRYING),
      ],
    };
    compareData = compareWithItemWritten(minutesAgo(1));

    renderStatus();

    expect(screen.getByText("Scoring failed, retrying")).toBeInTheDocument();
  });

  it("should say the item is retrying while a judge retries, though another judge already gave the run results", () => {
    statusesByTrace = {
      "trace-1": [judgeStatus(ONLINE_SCORING_STATE.RETRYING)],
    };
    compareData = compareWithRuns({ traceId: "trace-1", scored: true });

    renderStatus();

    expect(screen.getByText("Scoring failed, retrying")).toBeInTheDocument();
  });

  it("should count the wait from the run written last when an item runs several times", () => {
    compareData = {
      content: [
        {
          experiment_items: [
            {
              dataset_item_id: DATASET_ITEM_ID,
              created_at: minutesAgo(10),
              trace_id: "trace-1",
            },
            {
              dataset_item_id: DATASET_ITEM_ID,
              created_at: minutesAgo(1),
              trace_id: "trace-2",
            },
          ],
        },
      ],
    };

    renderStatus();

    expect(pollWith(compareData)).toBe(5000);
  });
});
