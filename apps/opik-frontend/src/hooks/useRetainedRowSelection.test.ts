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

  it("removes an off-page selection when controlled selection sets that id to false", () => {
    const page1: Row[] = [
      { id: "a", name: "alpha" },
      { id: "b", name: "beta" },
    ];

    const { result, rerender } = renderHook(
      ({ rows, scope }) => useRetainedRowSelection({ rows, scope }),
      {
        initialProps: {
          rows: page1,
          scope: { projectId: "p1" },
        },
      },
    );

    act(() => {
      result.current.setRowSelection({ a: true, b: true });
    });

    // Paginate away from both selected rows so they are retained off-page.
    rerender({
      rows: [{ id: "c", name: "gamma" }],
      scope: { projectId: "p1" },
    });

    expect(result.current.selectedRows).toEqual([
      { id: "a", name: "alpha" },
      { id: "b", name: "beta" },
    ]);

    act(() => {
      result.current.setRowSelection({ a: true, b: false });
    });

    expect(result.current.rowSelection).toEqual({ a: true, b: false });
    expect(result.current.selectedRows).toEqual([{ id: "a", name: "alpha" }]);
  });

  it("retains off-page selections when the current page becomes empty", () => {
    const page1: Row[] = [{ id: "a", name: "alpha" }];

    const { result, rerender } = renderHook(
      ({ rows, scope }) => useRetainedRowSelection({ rows, scope }),
      {
        initialProps: {
          rows: page1,
          scope: { projectId: "p1", search: "" },
        },
      },
    );

    act(() => {
      result.current.setRowSelection({ a: true });
    });

    rerender({
      rows: [],
      scope: { projectId: "p1", search: "" },
    });

    expect(result.current.rowSelection).toEqual({ a: true });
    expect(result.current.selectedRows).toEqual([{ id: "a", name: "alpha" }]);
  });

  it("drops retained payloads for ids deleted from the authoritative dataset", () => {
    const page1: Row[] = [
      { id: "a", name: "alpha" },
      { id: "b", name: "beta" },
    ];

    const { result, rerender } = renderHook(
      ({
        rows,
        scope,
        deletedIds,
      }: {
        rows: Row[];
        scope: Record<string, unknown>;
        deletedIds?: ReadonlySet<string>;
      }) => useRetainedRowSelection({ rows, scope, deletedIds }),
      {
        initialProps: {
          rows: page1,
          scope: { projectId: "p1" } as Record<string, unknown>,
          deletedIds: undefined as ReadonlySet<string> | undefined,
        },
      },
    );

    act(() => {
      result.current.setRowSelection({ a: true, b: true });
    });

    // Leave page so both are retained off-page.
    rerender({
      rows: [{ id: "c", name: "gamma" }],
      scope: { projectId: "p1" },
      deletedIds: undefined,
    });

    expect(result.current.selectedRows).toHaveLength(2);

    // Authoritative delete of "a" only — "b" must remain selectable for bulk actions.
    rerender({
      rows: [{ id: "c", name: "gamma" }],
      scope: { projectId: "p1" },
      deletedIds: new Set(["a"]),
    });

    expect(result.current.rowSelection.a).toBeFalsy();
    expect(result.current.selectedRows).toEqual([{ id: "b", name: "beta" }]);
  });
});
