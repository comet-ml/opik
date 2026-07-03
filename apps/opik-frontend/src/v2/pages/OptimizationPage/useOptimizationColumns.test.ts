import { describe, it, expect, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import { useOptimizationColumns } from "./useOptimizationColumns";
import type { TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const renderColumns = () =>
  renderHook(() =>
    useOptimizationColumns({
      experiments: [],
      columnsOrder: [],
      selectedColumns: [],
      sortableBy: [],
      statusMap: new Map<string, TrialStatus>([["c-1", "passed"]]),
      onViewPromptDiff: vi.fn(),
    }),
  );

describe("useOptimizationColumns", () => {
  it("does not include the Trial items (trace_count) column", () => {
    const { result } = renderColumns();
    const ids = result.current.columnsDef.map((c) => c.id);
    expect(ids).not.toContain("trace_count");
  });

  it("passes the status map and diff opener through column meta", () => {
    const { result } = renderColumns();
    const statusColumn = result.current.columnsDef.find(
      (c) => c.id === "trial_status",
    );
    expect(statusColumn?.customMeta).toMatchObject({
      statusMap: expect.any(Map),
    });

    const promptColumn = result.current.columnsDef.find(
      (c) => c.id === "prompt",
    );
    expect(promptColumn?.customMeta).toMatchObject({
      onViewPromptDiff: expect.any(Function),
    });
  });

  it("assigns cell components without as-never casts", () => {
    const { result } = renderColumns();
    // Every column with a cell holds a callable renderer (the typed component).
    result.current.columnsDef
      .filter((c) => c.cell !== undefined)
      .forEach((c) => expect(typeof c.cell).toBe("function"));
  });
});
