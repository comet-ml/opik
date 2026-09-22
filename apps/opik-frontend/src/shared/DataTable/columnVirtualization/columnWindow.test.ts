import { describe, it, expect } from "vitest";

import {
  sliceColumnWindow,
  sliceColumnWindowHeaders,
  isColumnSpacer,
  INACTIVE_COLUMN_WINDOW,
  ColumnWindow,
} from "./columnWindow";

const items = ["l0", "l1", "c0", "c1", "c2", "c3", "c4", "r0"];

describe("sliceColumnWindow", () => {
  it("returns items unchanged when window is inactive", () => {
    expect(sliceColumnWindow(items, INACTIVE_COLUMN_WINDOW)).toBe(items);
  });

  it("inserts lead and trail spacers around the windowed center columns", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 2,
      centerCount: 5,
      start: 1,
      end: 3,
      leadingWidth: 100,
      trailingWidth: 50,
    };

    const result = sliceColumnWindow(items, window);

    expect(result).toEqual([
      "l0",
      "l1",
      { id: "__column_spacer_lead", size: 100, isColumnSpacer: true },
      "c1",
      "c2",
      "c3",
      { id: "__column_spacer_trail", size: 50, isColumnSpacer: true },
      "r0",
    ]);
  });

  it("omits the lead spacer when the window starts at the first center column", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 2,
      centerCount: 5,
      start: 0,
      end: 2,
      leadingWidth: 0,
      trailingWidth: 50,
    };

    const result = sliceColumnWindow(items, window);

    expect(result).toEqual([
      "l0",
      "l1",
      "c0",
      "c1",
      "c2",
      { id: "__column_spacer_trail", size: 50, isColumnSpacer: true },
      "r0",
    ]);
  });

  it("omits the trail spacer when the window ends at the last center column", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 2,
      centerCount: 5,
      start: 3,
      end: 4,
      leadingWidth: 100,
      trailingWidth: 0,
    };

    const result = sliceColumnWindow(items, window);

    expect(result).toEqual([
      "l0",
      "l1",
      { id: "__column_spacer_lead", size: 100, isColumnSpacer: true },
      "c3",
      "c4",
      "r0",
    ]);
  });

  it("returns only spacers for an empty items array", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 0,
      centerCount: 0,
      start: 0,
      end: -1,
      leadingWidth: 100,
      trailingWidth: 50,
    };

    expect(sliceColumnWindow([], window)).toEqual([
      { id: "__column_spacer_lead", size: 100, isColumnSpacer: true },
      { id: "__column_spacer_trail", size: 50, isColumnSpacer: true },
    ]);
  });

  it("does not throw when start/end exceed the actual item count", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 2,
      centerCount: 100,
      start: 90,
      end: 120,
      leadingWidth: 100,
      trailingWidth: 0,
    };

    expect(() => sliceColumnWindow(items, window)).not.toThrow();
    expect(sliceColumnWindow(items, window)).toEqual([
      "l0",
      "l1",
      { id: "__column_spacer_lead", size: 100, isColumnSpacer: true },
    ]);
  });

  it("keeps all pinned left and right columns outside the windowed range", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 2,
      centerCount: 5,
      start: 2,
      end: 2,
      leadingWidth: 200,
      trailingWidth: 100,
    };

    const result = sliceColumnWindow(items, window);

    expect(result[0]).toBe("l0");
    expect(result[1]).toBe("l1");
    expect(result[result.length - 1]).toBe("r0");
  });
});

describe("isColumnSpacer", () => {
  it("identifies column spacer objects", () => {
    expect(isColumnSpacer({ id: "s", size: 10, isColumnSpacer: true })).toBe(
      true,
    );
  });

  it("rejects non-spacer values", () => {
    expect(isColumnSpacer("c0")).toBe(false);
    expect(isColumnSpacer(null)).toBe(false);
    expect(isColumnSpacer(undefined)).toBe(false);
    expect(isColumnSpacer({ id: "s", size: 10 })).toBe(false);
  });
});

describe("sliceColumnWindowHeaders", () => {
  const groupRow = [
    { id: "select", colSpan: 1 },
    { id: "dataset", colSpan: 2 },
    { id: "evaluation", colSpan: 4 },
    { id: "scores", colSpan: 320 },
  ];

  it("passes headers through untouched when the window is inactive", () => {
    expect(sliceColumnWindowHeaders(groupRow, INACTIVE_COLUMN_WINDOW)).toEqual(
      groupRow.map((header) => ({ header, colSpan: header.colSpan })),
    );
  });

  it("shrinks a group to the leaves inside the window and drops the rest", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 1,
      centerCount: 326,
      start: 100,
      end: 114,
      leadingWidth: 5000,
      trailingWidth: 9000,
    };

    const result = sliceColumnWindowHeaders(groupRow, window);

    expect(result).toEqual([
      { header: groupRow[0], colSpan: 1 },
      { id: "__column_spacer_lead", size: 5000, isColumnSpacer: true },
      { header: groupRow[3], colSpan: 15 },
      { id: "__column_spacer_trail", size: 9000, isColumnSpacer: true },
    ]);
  });

  it("keeps every group whose leaves straddle the window edges", () => {
    const window: ColumnWindow = {
      active: true,
      leftCount: 1,
      centerCount: 326,
      start: 0,
      end: 8,
      leadingWidth: 0,
      trailingWidth: 9000,
    };

    const result = sliceColumnWindowHeaders(groupRow, window);

    expect(result).toEqual([
      { header: groupRow[0], colSpan: 1 },
      { header: groupRow[1], colSpan: 2 },
      { header: groupRow[2], colSpan: 4 },
      { header: groupRow[3], colSpan: 3 },
      { id: "__column_spacer_trail", size: 9000, isColumnSpacer: true },
    ]);
  });

  it("gives each segment of a group split by a pinned boundary its own key", () => {
    const straddling = [
      { id: "select", colSpan: 1 },
      { id: "scores", colSpan: 4 },
    ];
    const window: ColumnWindow = {
      active: true,
      leftCount: 1,
      centerCount: 3,
      start: 0,
      end: 1,
      leadingWidth: 0,
      trailingWidth: 700,
    };

    const result = sliceColumnWindowHeaders(straddling, window);

    expect(result).toEqual([
      { header: straddling[0], colSpan: 1 },
      { header: straddling[1], colSpan: 2 },
      { id: "__column_spacer_trail", size: 700, isColumnSpacer: true },
      { header: straddling[1], colSpan: 1, key: "scores__2" },
    ]);
  });

  it("slices a leaf header row the same way sliceColumnWindow does", () => {
    const leaves = items.map((id) => ({ id, colSpan: 1 }));
    const window: ColumnWindow = {
      active: true,
      leftCount: 2,
      centerCount: 5,
      start: 1,
      end: 3,
      leadingWidth: 100,
      trailingWidth: 50,
    };

    const result = sliceColumnWindowHeaders(leaves, window);

    expect(result).toEqual([
      { header: leaves[0], colSpan: 1 },
      { header: leaves[1], colSpan: 1 },
      { id: "__column_spacer_lead", size: 100, isColumnSpacer: true },
      { header: leaves[3], colSpan: 1 },
      { header: leaves[4], colSpan: 1 },
      { header: leaves[5], colSpan: 1 },
      { id: "__column_spacer_trail", size: 50, isColumnSpacer: true },
      { header: leaves[7], colSpan: 1 },
    ]);
  });
});
