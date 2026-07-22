import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";

import { useOptimizationColumns } from "./useOptimizationColumns";

const renderColumns = () =>
  renderHook(() =>
    useOptimizationColumns({
      experiments: [],
      columnsOrder: [],
      selectedColumns: [],
      sortableBy: [],
      statusMap: new Map(),
    }),
  );

describe("useOptimizationColumns", () => {
  it("keeps Trial items (trace_count) available in the column set", () => {
    // The column stays in the picker; it is only out of the default selection
    // (see DEFAULT_SELECTED_COLUMNS in useOptimizationTableState).
    const { result } = renderColumns();
    const ids = result.current.columnsDef.map((c) => c.id);
    expect(ids).toContain("trace_count");
  });

  it("passes the shared status map through column meta", () => {
    const { result } = renderColumns();
    const statusColumn = result.current.columnsDef.find(
      (c) => c.id === "trial_status",
    );
    expect(statusColumn?.customMeta).toMatchObject({
      statusMap: expect.any(Map),
    });
  });

  it("assigns cell components without as-never casts", () => {
    const { result } = renderColumns();
    result.current.columnsDef
      .filter((c) => c.cell !== undefined)
      .forEach((c) => expect(typeof c.cell).toBe("function"));
  });
});
