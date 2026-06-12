import { describe, expect, it } from "vitest";

import shouldLoadFullSpansData from "@/api/traces/shouldLoadFullSpansData";
import { COLUMN_CUSTOM_ID, COLUMN_TYPE } from "@/types/shared";

describe("shouldLoadFullSpansData", () => {
  it("loads full spans for trace panel search", () => {
    expect(shouldLoadFullSpansData("payload term", [])).toBe(true);
    expect(shouldLoadFullSpansData("   ", [])).toBe(false);
  });

  it("loads full spans for direct payload filters", () => {
    expect(
      shouldLoadFullSpansData(undefined, [
        {
          id: "input-filter",
          field: "input",
          operator: "contains",
          type: COLUMN_TYPE.string,
          value: "prompt",
        },
      ]),
    ).toBe(true);
  });

  it("loads full spans for custom payload path filters", () => {
    expect(
      shouldLoadFullSpansData(undefined, [
        {
          id: "custom-filter",
          field: COLUMN_CUSTOM_ID,
          key: "output.answer",
          operator: "contains",
          type: COLUMN_TYPE.dictionary,
          value: "42",
        },
      ]),
    ).toBe(true);
  });

  it("keeps lightweight spans for non-payload filters", () => {
    expect(
      shouldLoadFullSpansData(undefined, [
        {
          id: "name-filter",
          field: "name",
          operator: "contains",
          type: COLUMN_TYPE.string,
          value: "span",
        },
      ]),
    ).toBe(false);
  });
});
