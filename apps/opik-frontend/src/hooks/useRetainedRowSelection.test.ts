import { act, renderHook } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import useRetainedRowSelection from "./useRetainedRowSelection";

type Row = { id: string; name: string };

describe("useRetainedRowSelection", () => {
  it("retains selected row payloads across row list updates", () => {
    const initialRows: Row[] = [
      { id: "a", name: "alpha" },
      { id: "b", name: "beta" },
    ];

    const { result, rerender } = renderHook(
      ({ rows, scope }) => useRetainedRowSelection({ rows, scope }),
      {
        initialProps: {
          rows: initialRows,
          scope: { projectId: "p1", search: "" },
        },
      },
    );

    act(() => {
      result.current.setRowSelection({ a: true });
    });

    expect(result.current.selectedRows).toEqual([{ id: "a", name: "alpha" }]);

    rerender({
      rows: [{ id: "b", name: "beta" }],
      scope: { projectId: "p1", search: "" },
    });

    expect(result.current.selectedRows).toEqual([{ id: "a", name: "alpha" }]);
    expect(result.current.rowSelection).toEqual({ a: true });
  });

  it("clears selection when scope changes", () => {
    const rows: Row[] = [{ id: "a", name: "alpha" }];

    const { result, rerender } = renderHook(
      ({ rows, scope }) => useRetainedRowSelection({ rows, scope }),
      {
        initialProps: {
          rows,
          scope: { projectId: "p1", type: "traces" } as Record<string, unknown>,
        },
      },
    );

    act(() => {
      result.current.setRowSelection({ a: true });
    });

    expect(result.current.selectedRows).toHaveLength(1);

    rerender({
      rows,
      scope: { projectId: "p1", type: "spans" },
    });

    expect(result.current.rowSelection).toEqual({});
    expect(result.current.selectedRows).toEqual([]);
  });

  it("clearRowSelection resets selection state", () => {
    const rows: Row[] = [{ id: "a", name: "alpha" }];

    const { result } = renderHook(() =>
      useRetainedRowSelection({
        rows,
        scope: { projectId: "p1" },
      }),
    );

    act(() => {
      result.current.setRowSelection({ a: true });
    });

    act(() => {
      result.current.clearRowSelection();
    });

    expect(result.current.rowSelection).toEqual({});
    expect(result.current.selectedRows).toEqual([]);
  });
});
