import { describe, expect, it } from "vitest";
import {
  formatSingleSelectSummary,
  isSingleSelectApplied,
  singleSelectToFilters,
} from "./SingleSelectChip.logic";
import { SingleSelectChipDefinition } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";

const def: SingleSelectChipDefinition = {
  id: "type",
  field: "type",
  label: "Type",
  kind: "single-select",
  options: [
    { label: "Thread", value: "thread" },
    { label: "Trace", value: "trace" },
  ],
};

describe("isSingleSelectApplied", () => {
  it("returns false when value is undefined", () => {
    expect(isSingleSelectApplied(undefined)).toBe(false);
  });

  it("returns false when value.value is empty string", () => {
    expect(isSingleSelectApplied({ value: "" })).toBe(false);
  });

  it("returns true when value.value is a non-empty string", () => {
    expect(isSingleSelectApplied({ value: "thread" })).toBe(true);
  });
});

describe("singleSelectToFilters", () => {
  it("returns empty array when not applied", () => {
    expect(singleSelectToFilters(undefined, def)).toEqual([]);
    expect(singleSelectToFilters({ value: "" }, def)).toEqual([]);
  });

  it("emits a single Filter with default operator '='", () => {
    const result = singleSelectToFilters({ value: "thread" }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({
      field: "type",
      operator: "=",
      value: "thread",
      key: "",
    });
    expect(result[0].id).toBeTruthy();
  });

  it("honors override operator on the definition", () => {
    const withOperator: SingleSelectChipDefinition = {
      ...def,
      operator: "!=" as never,
    };
    const result = singleSelectToFilters({ value: "trace" }, withOperator);
    expect(result[0].operator).toBe("!=");
  });

  it("propagates columnType when provided", () => {
    const typed: SingleSelectChipDefinition = {
      ...def,
      columnType: COLUMN_TYPE.string,
    };
    const result = singleSelectToFilters({ value: "thread" }, typed);
    expect(result[0].type).toBe(COLUMN_TYPE.string);
  });
});

describe("formatSingleSelectSummary", () => {
  it("returns null when not applied", () => {
    expect(formatSingleSelectSummary(undefined, def)).toBeNull();
    expect(formatSingleSelectSummary({ value: "" }, def)).toBeNull();
  });

  it("returns the option label when value matches", () => {
    expect(formatSingleSelectSummary({ value: "thread" }, def)).toBe("Thread");
  });

  it("falls back to raw value when no matching option", () => {
    expect(formatSingleSelectSummary({ value: "unknown" }, def)).toBe(
      "unknown",
    );
  });
});
