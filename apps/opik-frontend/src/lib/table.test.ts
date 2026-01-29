import { describe, expect, it, vi } from "vitest";
import { injectColumnCallback } from "./table";
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
