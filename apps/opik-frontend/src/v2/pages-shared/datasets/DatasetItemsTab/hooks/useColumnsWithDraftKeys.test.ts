import { describe, expect, it } from "vitest";

import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { mergeColumnsWithDraftData } from "./useColumnsWithDraftKeys";

describe("mergeColumnsWithDraftData", () => {
  const apiColumns = [{ name: "input", types: [DYNAMIC_COLUMN_TYPE.string] }];

  it("returns the same array when drafts add no new keys", () => {
    const result = mergeColumnsWithDraftData(apiColumns, [
      { input: "a" },
      undefined,
    ]);

    expect(result).toBe(apiColumns);
  });

  it("appends draft-only keys with the types seen across drafts", () => {
    const result = mergeColumnsWithDraftData(apiColumns, [
      { input: "a", score: 1, meta: { a: 1 } },
      { score: "high", tags: ["x"] },
    ]);

    expect(result).toEqual([
      ...apiColumns,
      {
        name: "score",
        types: [DYNAMIC_COLUMN_TYPE.number, DYNAMIC_COLUMN_TYPE.string],
      },
      { name: "meta", types: [DYNAMIC_COLUMN_TYPE.object] },
      { name: "tags", types: [DYNAMIC_COLUMN_TYPE.array] },
    ]);
  });
});
