import { describe, expect, it } from "vitest";
import {
  formatNumericSummary,
  isNumericApplied,
  numericToFilters,
} from "./NumericChip.logic";
import { NumericChipDefinition } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";

const def: NumericChipDefinition = {
  id: "duration",
  field: "duration",
  label: "Duration",
  kind: "numeric",
  columnType: COLUMN_TYPE.duration,
  format: "duration",
};

const integerDef: NumericChipDefinition = {
  id: "tokens",
  field: "usage.total_tokens",
  label: "Tokens",
  kind: "numeric",
  columnType: COLUMN_TYPE.number,
  format: "integer",
};

const currencyDef: NumericChipDefinition = {
  id: "cost",
  field: "total_estimated_cost",
  label: "Cost",
  kind: "numeric",
  columnType: COLUMN_TYPE.cost,
  format: "currency",
};

describe("isNumericApplied", () => {
  it("returns false for undefined or NaN inputs", () => {
    expect(isNumericApplied(undefined)).toBe(false);
    expect(isNumericApplied({ mode: "exactly", exact: NaN })).toBe(false);
  });

  it("returns true for each valid mode", () => {
    expect(isNumericApplied({ mode: "exactly", exact: 100 })).toBe(true);
    expect(isNumericApplied({ mode: "atLeast", min: 50 })).toBe(true);
    expect(isNumericApplied({ mode: "atMost", max: 200 })).toBe(true);
    expect(isNumericApplied({ mode: "between", min: 50, max: 200 })).toBe(true);
  });

  it("rejects between when min > max", () => {
    expect(isNumericApplied({ mode: "between", min: 300, max: 100 })).toBe(
      false,
    );
  });
});

describe("numericToFilters", () => {
  it("emits a single = filter for exactly mode", () => {
    const result = numericToFilters({ mode: "exactly", exact: 100 }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({
      field: "duration",
      operator: "=",
      value: "100",
    });
  });

  it("emits a >= filter for atLeast mode", () => {
    const result = numericToFilters({ mode: "atLeast", min: 50 }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ operator: ">=", value: "50" });
  });

  it("emits a <= filter for atMost mode", () => {
    const result = numericToFilters({ mode: "atMost", max: 200 }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ operator: "<=", value: "200" });
  });

  it("emits two filters for between mode (>= min AND <= max)", () => {
    const result = numericToFilters(
      { mode: "between", min: 50, max: 200 },
      def,
    );
    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({ operator: ">=", value: "50" });
    expect(result[1]).toMatchObject({ operator: "<=", value: "200" });
  });

  it("returns empty for invalid values", () => {
    expect(numericToFilters(undefined, def)).toEqual([]);
    expect(numericToFilters({ mode: "exactly", exact: NaN }, def)).toEqual([]);
  });
});

describe("formatNumericSummary", () => {
  it("formats duration with seconds suffix and one decimal", () => {
    expect(formatNumericSummary({ mode: "exactly", exact: 0.3 }, def)).toBe(
      "= 0.3s",
    );
    expect(formatNumericSummary({ mode: "atLeast", min: 1.5 }, def)).toBe(
      "≥ 1.5s",
    );
    expect(formatNumericSummary({ mode: "atMost", max: 2 }, def)).toBe(
      "≤ 2.0s",
    );
    expect(
      formatNumericSummary({ mode: "between", min: 0.5, max: 2 }, def),
    ).toBe("0.5s – 2.0s");
  });

  it("formats integers with no decimals or affixes", () => {
    expect(
      formatNumericSummary({ mode: "exactly", exact: 100 }, integerDef),
    ).toBe("= 100");
    expect(
      formatNumericSummary({ mode: "between", min: 10, max: 50 }, integerDef),
    ).toBe("10 – 50");
  });

  it("formats currency with $ prefix and two decimals", () => {
    expect(
      formatNumericSummary({ mode: "atMost", max: 0.01 }, currencyDef),
    ).toBe("≤ $0.01");
    expect(
      formatNumericSummary({ mode: "exactly", exact: 5 }, currencyDef),
    ).toBe("= $5.00");
  });

  it("returns null when not applied", () => {
    expect(formatNumericSummary(undefined, def)).toBeNull();
  });
});
