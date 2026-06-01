import { describe, expect, it } from "vitest";
import {
  formatPseudoSearchSummary,
  isPseudoSearchApplied,
  pseudoSearchToFilters,
} from "./PseudoSearchChip.logic";
import { PseudoSearchChipDefinition } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";

const containsDef: PseudoSearchChipDefinition = {
  id: "first_message",
  field: "first_message",
  label: "First message",
  kind: "pseudo-search",
  searchMode: "contains",
  columnType: COLUMN_TYPE.string,
};

const equalsDef: PseudoSearchChipDefinition = {
  id: "id",
  field: "id",
  label: "ID",
  kind: "pseudo-search",
  searchMode: "equals",
  columnType: COLUMN_TYPE.string,
};

describe("isPseudoSearchApplied", () => {
  it("returns false when value is undefined", () => {
    expect(isPseudoSearchApplied(undefined)).toBe(false);
  });

  it("returns false for empty / whitespace-only strings", () => {
    expect(isPseudoSearchApplied({ value: "" })).toBe(false);
    expect(isPseudoSearchApplied({ value: "   " })).toBe(false);
  });

  it("returns true for a non-empty trimmed string", () => {
    expect(isPseudoSearchApplied({ value: "hello" })).toBe(true);
    expect(isPseudoSearchApplied({ value: "  hello  " })).toBe(true);
  });
});

describe("pseudoSearchToFilters", () => {
  it("returns empty when not applied", () => {
    expect(pseudoSearchToFilters(undefined, containsDef)).toEqual([]);
    expect(pseudoSearchToFilters({ value: "" }, containsDef)).toEqual([]);
  });

  it("emits a contains filter for searchMode='contains'", () => {
    const result = pseudoSearchToFilters({ value: "hello" }, containsDef);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({
      field: "first_message",
      operator: "contains",
      value: "hello",
    });
  });

  it("emits an = filter for searchMode='equals'", () => {
    const result = pseudoSearchToFilters({ value: "abc-123" }, equalsDef);
    expect(result[0].operator).toBe("=");
    expect(result[0].value).toBe("abc-123");
  });

  it("trims whitespace from the value", () => {
    const result = pseudoSearchToFilters(
      { value: "  hello world  " },
      containsDef,
    );
    expect(result[0].value).toBe("hello world");
  });
});

describe("formatPseudoSearchSummary", () => {
  it("returns null when not applied", () => {
    expect(formatPseudoSearchSummary(undefined, containsDef)).toBeNull();
    expect(formatPseudoSearchSummary({ value: "  " }, containsDef)).toBeNull();
  });

  it("returns the trimmed value for text (contains) searches", () => {
    expect(formatPseudoSearchSummary({ value: "  hello  " }, containsDef)).toBe(
      "hello",
    );
  });

  it("truncates ID-shaped values to the last 3 characters", () => {
    expect(
      formatPseudoSearchSummary(
        { value: "0193abcd-ef01-2345-6789-abcdef012768" },
        equalsDef,
      ),
    ).toBe("...768");
  });

  it("leaves short ID-shaped values intact", () => {
    expect(formatPseudoSearchSummary({ value: "ab" }, equalsDef)).toBe("ab");
  });
});
