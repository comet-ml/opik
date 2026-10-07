import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";

import { EXPERIMENT_STATUS } from "@/types/datasets";
import usePlaygroundExperimentItem from "./usePlaygroundExperimentItem";

const EXPERIMENT_ID = "experiment-1";
const DATASET_ID = "dataset-1";
const DATASET_ITEM_ID = "item-1";

let experimentStatus: string;
let finishedAt: string | null;
/** When the rows and the finish stamp were each read, which the hook orders against each other. */
let rowsReadAt: number;
let experimentReadAt: number;

vi.mock("@/api/datasets/useExperimentById", () => ({
  default: () => ({
    data: {
      id: EXPERIMENT_ID,
      status: experimentStatus,
      finished_at: finishedAt,
    },
    dataUpdatedAt: experimentReadAt,
  }),
}));

// Empty by default: the shape a stopped run leaves behind for everything it never reached.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let rows: any[];

vi.mock("@/api/datasets/useCompareExperimentsList", () => ({
  default: () => ({ data: { content: rows }, dataUpdatedAt: rowsReadAt }),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
}));

vi.mock("@/store/PlaygroundStore", () => ({
  useDatasetFilters: () => [],
  useDatasetPage: () => 1,
  useDatasetSize: () => 10,
}));

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return React.createElement(
    QueryClientProvider,
    { client: queryClient },
    children,
  );
};

const renderItem = () =>
  renderHook(
    () =>
      usePlaygroundExperimentItem(EXPERIMENT_ID, DATASET_ITEM_ID, DATASET_ID),
    { wrapper },
  );

describe("usePlaygroundExperimentItem", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    experimentStatus = EXPERIMENT_STATUS.CANCELLED;
    finishedAt = new Date().toISOString();
    experimentReadAt = Date.now();
    rowsReadAt = experimentReadAt + 1_000;
    rows = [];
  });

  it("should report a row the stopped run never reached as not run", async () => {
    const { result } = renderItem();

    await waitFor(() => expect(result.current.notRun).toBe(true));
    expect(result.current.cancelled).toBe(true);
    expect(result.current.hasItem).toBe(false);
  });

  // The table unmounts rows that scroll out of view. A wait measured from mount restarted every
  // time one scrolled back, so a settled row showed its loader again for the length of the grace.
  it("should stay settled when the cell remounts long after the run ended", async () => {
    const first = renderItem();
    await waitFor(() => expect(first.result.current.notRun).toBe(true));
    first.unmount();

    const { result } = renderItem();

    await waitFor(() => expect(result.current.notRun).toBe(true));
  });

  // A stopped run's status is written the moment the stop is asked for, while items already with a
  // provider are still landing. Only the finish stamp says those have had their chance.
  it("should keep waiting while a stopped run may still be landing items", async () => {
    finishedAt = null;

    const { result } = renderItem();

    await waitFor(() => expect(result.current.hasItem).toBe(false));
    expect(result.current.notRun).toBe(false);
  });

  it("should keep waiting while the run is still going", async () => {
    experimentStatus = EXPERIMENT_STATUS.RUNNING;
    finishedAt = null;

    const { result } = renderItem();

    await waitFor(() =>
      expect(result.current).toEqual(
        expect.objectContaining({ hasItem: false }),
      ),
    );
    expect(result.current.notRun).toBe(false);
  });

  // A cell scrolling back into view refetches the run, and a read time that moved forward with it
  // would overtake the rows' own and put the loader back on a row already settled.
  it("should stay settled when the run is read again after the rows", async () => {
    const { result, rerender } = renderItem();
    await waitFor(() => expect(result.current.notRun).toBe(true));

    experimentReadAt = rowsReadAt + 1_000;
    rerender();

    expect(result.current.notRun).toBe(true);
  });

  // The stamp and the rows come from separate queries. Rows read before the stamp cannot show the
  // items written just before it, and concluding from them flashed "Cancelled" over a row that was
  // about to render its output.
  it("should not call a row unrun from rows read before the finish was known", async () => {
    rowsReadAt = experimentReadAt - 1_000;

    const { result } = renderItem();

    await waitFor(() => expect(result.current.hasItem).toBe(false));
    expect(result.current.notRun).toBe(false);
  });

  // What the run wrote for this row, read back out of a page that also holds other rows' items and
  // earlier attempts at this one.
  describe("reading the row's own item back", () => {
    const item = (overrides: Record<string, unknown>) => ({
      dataset_item_id: DATASET_ITEM_ID,
      created_at: "2026-10-01T00:00:00Z",
      trace_id: "trace-old",
      output: { output: "an older answer" },
      ...overrides,
    });

    it("should take the newest attempt and count the ones before it", async () => {
      rows = [
        {
          experiment_items: [
            item({}),
            { ...item({}), dataset_item_id: "other" },
          ],
        },
        {
          experiment_items: [
            item({
              created_at: "2026-10-02T00:00:00Z",
              trace_id: "trace-new",
              output: { error: { message: "provider refused the request" } },
            }),
          ],
        },
      ];

      const { result } = renderItem();

      await waitFor(() => expect(result.current.hasItem).toBe(true));
      expect(result.current.error).toBe("provider refused the request");
      expect(result.current.output).toBeNull();
      expect(result.current.traceId).toBe("trace-new");
      // The unrelated row's item is not this row's attempt.
      expect(result.current.runCount).toBe(2);
    });
  });
});
