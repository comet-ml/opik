import { describe, expect, it } from "vitest";
import { booleanToFilters, isBooleanApplied } from "./BooleanChip.logic";
import { BooleanChipDefinition } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";

const def: BooleanChipDefinition = {
  id: "has_queue",
  field: "annotation_queue_ids",
  label: "With annotation queue",
  kind: "boolean",
  onOperator: "is_not_empty",
  columnType: COLUMN_TYPE.list,
};

describe("isBooleanApplied", () => {
  it("returns false when value is undefined", () => {
    expect(isBooleanApplied(undefined)).toBe(false);
  });

  it("returns true when value.applied is true", () => {
    expect(isBooleanApplied({ applied: true })).toBe(true);
  });
});

describe("booleanToFilters", () => {
  it("returns empty when not applied", () => {
    expect(booleanToFilters(undefined, def)).toEqual([]);
  });

  it("emits a filter with the definition's onOperator and empty value", () => {
    const result = booleanToFilters({ applied: true }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({
      field: "annotation_queue_ids",
      operator: "is_not_empty",
      value: "",
      type: COLUMN_TYPE.list,
    });
    expect(result[0].id).toBeTruthy();
  });

  it("honors onValue when provided", () => {
    const withValue: BooleanChipDefinition = {
      ...def,
      onOperator: "=" as never,
      onValue: "true",
    };
    const result = booleanToFilters({ applied: true }, withValue);
    expect(result[0].operator).toBe("=");
    expect(result[0].value).toBe("true");
  });
});
