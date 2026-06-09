import { describe, expect, it } from "vitest";
import { COLUMN_TYPE } from "@/types/shared";
import { TRACE_FILTER_COLUMNS } from "./RuleFilteringSection";
import { normalizeFilters } from "./helpers";
import { isFilterValid } from "@/lib/filters";
import { Filter } from "@/types/filters";

const columns = TRACE_FILTER_COLUMNS as Parameters<typeof normalizeFilters>[1];

const createFilter = (
  overrides: Partial<Filter> &
    Pick<Filter, "id" | "field" | "operator" | "value">,
): Filter => ({
  type: COLUMN_TYPE.string,
  key: "",
  ...overrides,
});

describe("TRACE_FILTER_COLUMNS", () => {
  it("should contain expected column ids", () => {
    const ids = TRACE_FILTER_COLUMNS.map((c) => c.id);
    expect(ids).toContain("id");
    expect(ids).toContain("name");
    expect(ids).toContain("input");
    expect(ids).toContain("output");
    expect(ids).toContain("duration");
    expect(ids).toContain("metadata");
    expect(ids).toContain("tags");
    expect(ids).toContain("thread_id");
    expect(ids).toContain("feedback_scores");
  });

  it("should not contain separate input_json or output_json entries", () => {
    const ids = TRACE_FILTER_COLUMNS.map((c) => c.id);
    expect(ids).not.toContain("input_json");
    expect(ids).not.toContain("output_json");
  });

  it("should define input and output as dictionary type", () => {
    const input = TRACE_FILTER_COLUMNS.find((c) => c.id === "input");
    const output = TRACE_FILTER_COLUMNS.find((c) => c.id === "output");
    expect(input?.type).toBe(COLUMN_TYPE.dictionary);
    expect(output?.type).toBe(COLUMN_TYPE.dictionary);
  });
});

describe("normalizeFilters", () => {
  it("should map output_json field to output and preserve key", () => {
    const filters = [
      createFilter({
        id: "1",
        field: "output_json",
        type: COLUMN_TYPE.dictionary,
        operator: "is_not_empty",
        value: "",
        key: "output",
      }),
    ];
    const result = normalizeFilters(filters, columns);
    expect(result[0].field).toBe("output");
    expect(result[0].key).toBe("output");
    expect(result[0].type).toBe(COLUMN_TYPE.dictionary);
  });

  it("should map input_json field to input and preserve key", () => {
    const filters = [
      createFilter({
        id: "1",
        field: "input_json",
        type: COLUMN_TYPE.dictionary,
        operator: "contains",
        value: "hello",
        key: "context",
      }),
    ];
    const result = normalizeFilters(filters, columns);
    expect(result[0].field).toBe("input");
    expect(result[0].key).toBe("context");
  });

  it("should preserve saved type when present", () => {
    const filters = [
      createFilter({
        id: "1",
        field: "output",
        type: COLUMN_TYPE.string,
        operator: "contains",
        value: "test",
      }),
    ];
    const result = normalizeFilters(filters, columns);
    expect(result[0].type).toBe(COLUMN_TYPE.string);
  });

  it("should fall back to column type when filter has no type", () => {
    const filters = [
      {
        id: "1",
        field: "output",
        type: "",
        operator: "contains",
        value: "test",
        key: "",
      },
    ] as Filter[];
    const result = normalizeFilters(filters, columns);
    expect(result[0].type).toBe(COLUMN_TYPE.dictionary);
  });

  it("should return empty array for empty input", () => {
    expect(normalizeFilters([], columns)).toEqual([]);
  });

  it("should return empty array for null/undefined input", () => {
    expect(normalizeFilters(null as unknown as Filter[], columns)).toEqual([]);
    expect(normalizeFilters(undefined as unknown as Filter[], columns)).toEqual(
      [],
    );
  });
});

describe("rule editor key-optional validation for input/output", () => {
  const ruleFilterValid = (f: Filter) =>
    isFilterValid(
      (f.field === "input" || f.field === "output") && !f.key
        ? { ...f, type: COLUMN_TYPE.string }
        : f,
    );

  it("should accept input/output without key when value is present", () => {
    for (const field of ["input", "output"]) {
      const filter = createFilter({
        id: "1",
        field,
        type: COLUMN_TYPE.dictionary,
        operator: "contains",
        value: "hello",
        key: "",
      });
      expect(ruleFilterValid(filter)).toBe(true);
    }
  });

  it("should accept input/output with key and is_not_empty", () => {
    const filter = createFilter({
      id: "1",
      field: "input",
      type: COLUMN_TYPE.dictionary,
      operator: "is_not_empty",
      value: "",
      key: "context",
    });
    expect(ruleFilterValid(filter)).toBe(true);
  });

  it("should reject input/output without key and without value", () => {
    const filter = createFilter({
      id: "1",
      field: "input",
      type: COLUMN_TYPE.dictionary,
      operator: "contains",
      value: "",
      key: "",
    });
    expect(ruleFilterValid(filter)).toBe(false);
  });

  it("should reject input/output with key but without value", () => {
    const filter = createFilter({
      id: "1",
      field: "output",
      type: COLUMN_TYPE.dictionary,
      operator: "contains",
      value: "",
      key: "response",
    });
    expect(ruleFilterValid(filter)).toBe(false);
  });

  it("should still reject metadata without key", () => {
    const filter = createFilter({
      id: "1",
      field: "metadata",
      type: COLUMN_TYPE.dictionary,
      operator: "contains",
      value: "test",
      key: "",
    });
    expect(ruleFilterValid(filter)).toBe(false);
  });
});
