import { describe, expect, it } from "vitest";
import {
  formatQueryBuilderSummary,
  isQueryBuilderApplied,
  queryBuilderToFilters,
} from "./QueryBuilderChip.logic";
import { QueryBuilderChipDefinition } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";

const def: QueryBuilderChipDefinition = {
  id: "feedback_scores",
  field: "feedback_scores",
  label: "Feedback scores",
  kind: "query-builder",
  columnType: COLUMN_TYPE.numberDictionary,
  operators: [">=", "<=", "="],
};

const validRow: Filter = {
  id: "r1",
  field: "feedback_scores",
  type: COLUMN_TYPE.numberDictionary,
  operator: ">=",
  key: "Hallucination",
  value: "0.5",
};

const invalidRow: Filter = {
  id: "r2",
  field: "",
  type: "",
  operator: "",
  key: "",
  value: "",
};

describe("isQueryBuilderApplied", () => {
  it("returns false for undefined or empty rows", () => {
    expect(isQueryBuilderApplied(undefined)).toBe(false);
    expect(isQueryBuilderApplied({ rows: [] })).toBe(false);
  });

  it("returns false when no row passes isFilterValid", () => {
    expect(isQueryBuilderApplied({ rows: [invalidRow] })).toBe(false);
  });

  it("returns true when at least one row is valid", () => {
    expect(isQueryBuilderApplied({ rows: [validRow, invalidRow] })).toBe(true);
  });
});

describe("queryBuilderToFilters", () => {
  it("returns empty when nothing valid is present", () => {
    expect(queryBuilderToFilters(undefined, def)).toEqual([]);
    expect(queryBuilderToFilters({ rows: [invalidRow] }, def)).toEqual([]);
  });

  it("forces field + columnType from the definition onto each valid row", () => {
    const result = queryBuilderToFilters(
      {
        rows: [
          { ...validRow, field: "wrong", type: COLUMN_TYPE.string },
          invalidRow,
        ],
      },
      def,
    );
    expect(result).toHaveLength(1);
    expect(result[0].field).toBe("feedback_scores");
    expect(result[0].type).toBe(COLUMN_TYPE.numberDictionary);
    expect(result[0].operator).toBe(">=");
    expect(result[0].value).toBe("0.5");
    expect(result[0].key).toBe("Hallucination");
  });
});

describe("formatQueryBuilderSummary", () => {
  it("returns the count for 2+ valid rows", () => {
    expect(
      formatQueryBuilderSummary({ rows: [validRow, validRow, invalidRow] }, def)
        ?.display,
    ).toBe("2");
  });

  it("returns key + operator + value for a single valid row when key is configured", () => {
    const defWithKey: QueryBuilderChipDefinition = {
      ...def,
      key: { placeholder: "Select score" },
    };
    expect(
      formatQueryBuilderSummary({ rows: [validRow] }, defWithKey)?.tooltip,
    ).toBe("Hallucination at least 0.5");
    expect(
      formatQueryBuilderSummary({ rows: [validRow] }, defWithKey)?.display,
    ).toBe("Hallucination ≥ 0.5");
  });

  it("returns null when nothing is applied", () => {
    expect(formatQueryBuilderSummary(undefined, def)).toBeNull();
  });
});
