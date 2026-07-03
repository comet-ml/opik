import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";

import { useOptimizationColumns } from "./useOptimizationColumns";

const renderColumns = () =>
  renderHook(() =>
    useOptimizationColumns({
      candidates: [],
      experiments: [],
      columnsOrder: [],
      selectedColumns: [],
      sortableBy: [],
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

  it("passes the status inputs through column meta", () => {
    const { result } = renderColumns();
    const statusColumn = result.current.columnsDef.find(
      (c) => c.id === "trial_status",
    );
    expect(statusColumn?.customMeta).toMatchObject({
      candidates: expect.any(Array),
    });
  });

  it("assigns cell components without as-never casts", () => {
    const { result } = renderColumns();
    result.current.columnsDef
      .filter((c) => c.cell !== undefined)
      .forEach((c) => expect(typeof c.cell).toBe("function"));
  });
});
