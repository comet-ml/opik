import { describe, expect, it, vi } from "vitest";
import { injectColumnCallback, reconcileSelectedRows } from "./table";
import { ColumnDef } from "@tanstack/react-table";

describe("injectColumnCallback", () => {
  it("should inject callback while preserving existing custom metadata", () => {
    const mockCallback = vi.fn();
    const columns: ColumnDef<unknown>[] = [
      {
        id: "id",
        accessorKey: "id",
        meta: { custom: { asId: true, tooltip: "Click to view" } },
      },
      { id: "name", accessorKey: "name", meta: {} },
    ];

    const result = injectColumnCallback(columns, "id", mockCallback);

    expect(result[0].meta?.custom).toEqual({
      asId: true,
      tooltip: "Click to view",
      callback: mockCallback,
    });
    expect(result[1]).toEqual(columns[1]);
  });

  it("should return unchanged array when column not found", () => {
    const mockCallback = vi.fn();
    const columns: ColumnDef<unknown>[] = [
      { id: "id", accessorKey: "id", meta: {} },
    ];

    const result = injectColumnCallback(columns, "missing", mockCallback);

    expect(result).toBe(columns);
  });

  it("should handle columns with no existing meta", () => {
    const mockCallback = vi.fn();
    const columns: ColumnDef<unknown>[] = [{ id: "id", accessorKey: "id" }];

    const result = injectColumnCallback(columns, "id", mockCallback);

    expect(result[0].meta?.custom).toEqual({
      callback: mockCallback,
    });
  });

  it("should not mutate original columns array", () => {
    const mockCallback = vi.fn();
    const originalColumns: ColumnDef<unknown>[] = [
      { id: "id", accessorKey: "id", meta: { custom: { asId: true } } },
    ];
    const originalMeta = originalColumns[0].meta?.custom;

    injectColumnCallback(originalColumns, "id", mockCallback);

    expect(originalColumns[0].meta?.custom).toBe(originalMeta);
  });

  it("should merge additional metadata correctly", () => {
    const mockCallback = vi.fn();
    const columns: ColumnDef<unknown>[] = [
      { id: "id", accessorKey: "id", meta: { custom: { asId: true } } },
    ];

    const result = injectColumnCallback(columns, "id", mockCallback, {
      newProp: "value",
    });

    expect(result[0].meta?.custom).toEqual({
      asId: true,
      callback: mockCallback,
      newProp: "value",
    });
  });
});

describe("reconcileSelectedRows", () => {
  it("merges current-page rows into the map", () => {
    const map = new Map<string, { id: string; name: string }>();
    const rows = [
      { id: "a", name: "A" },
      { id: "b", name: "B" },
    ];
    const rowSelection = { a: true, b: true };

    const result = reconcileSelectedRows(map, rowSelection, rows);

    expect(result).toEqual(rows);
    expect(map.get("a")).toEqual({ id: "a", name: "A" });
    expect(map.get("b")).toEqual({ id: "b", name: "B" });
  });

  it("drops deselected IDs from the map", () => {
    const map = new Map([
      ["a", { id: "a", name: "A" }],
      ["b", { id: "b", name: "B" }],
    ]);
    const rows = [
      { id: "a", name: "A" },
      { id: "b", name: "B" },
    ];
    const rowSelection = { a: true, b: false };

    const result = reconcileSelectedRows(map, rowSelection, rows);

    expect(result).toEqual([{ id: "a", name: "A" }]);
    expect(map.has("a")).toBe(true);
    expect(map.has("b")).toBe(false);
  });

  it("preserves selections not on the current page", () => {
    const map = new Map([["off-page", { id: "off-page", name: "Off" }]]);
    const rows = [{ id: "a", name: "A" }];
    const rowSelection = { a: true, "off-page": true };
    const scopeKeyRef = { current: "scope-a" };

    const result = reconcileSelectedRows(map, rowSelection, rows, {
      scopeKey: "scope-a",
      scopeKeyRef,
    });

    expect(result).toEqual([
      { id: "a", name: "A" },
      { id: "off-page", name: "Off" },
    ]);
    expect(map.get("a")).toEqual({ id: "a", name: "A" });
    expect(map.get("off-page")).toEqual({ id: "off-page", name: "Off" });
  });

  it("clears retained rows when scopeKey changes", () => {
    const map = new Map([["off-page", { id: "off-page", name: "Off" }]]);
    const rows = [{ id: "a", name: "A" }];
    // Caller would also clear rowSelection on scope change; this models a
    // stale off-page id still present until that clear lands.
    const rowSelection = { a: true, "off-page": true };
    const scopeKeyRef = { current: "scope-a" };

    const result = reconcileSelectedRows(map, rowSelection, rows, {
      scopeKey: "scope-b",
      scopeKeyRef,
    });

    expect(scopeKeyRef.current).toBe("scope-b");
    expect(map.has("off-page")).toBe(false);
    expect(result).toEqual([{ id: "a", name: "A" }]);
    expect(map.get("a")).toEqual({ id: "a", name: "A" });
  });

  it("keeps the map when scopeKey is unchanged", () => {
    const map = new Map([["off-page", { id: "off-page", name: "Off" }]]);
    const rows = [{ id: "a", name: "A" }];
    const rowSelection = { a: true, "off-page": true };
    const scopeKeyRef = { current: "scope-a" };

    reconcileSelectedRows(map, rowSelection, rows, {
      scopeKey: "scope-a",
      scopeKeyRef,
    });

    expect(map.has("off-page")).toBe(true);
  });

  it("drops retained off-page ids listed in deletedIds", () => {
    const map = new Map([
      ["off-page", { id: "off-page", name: "Off" }],
      ["keep", { id: "keep", name: "Keep" }],
    ]);
    const rows = [{ id: "a", name: "A" }];
    const rowSelection = { a: true, "off-page": true, keep: true };
    const scopeKeyRef = { current: "scope-a" };

    const result = reconcileSelectedRows(map, rowSelection, rows, {
      scopeKey: "scope-a",
      scopeKeyRef,
      deletedIds: new Set(["off-page"]),
    });

    expect(map.has("off-page")).toBe(false);
    expect(map.has("keep")).toBe(true);
    expect(result).toEqual([
      { id: "a", name: "A" },
      { id: "keep", name: "Keep" },
    ]);
  });
});
