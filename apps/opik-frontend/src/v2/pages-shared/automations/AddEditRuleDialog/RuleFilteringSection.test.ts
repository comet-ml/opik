import { describe, expect, it } from "vitest";
import { COLUMN_TYPE } from "@/types/shared";
import { TRACE_FILTER_COLUMNS } from "./RuleFilteringSection";
import { buildRuleFilters, normalizeFilters } from "./helpers";
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

describe("duration filters are seconds in the dialog and milliseconds on the wire", () => {
  const durationFilter = (value: string | number, type: Filter["type"]) =>
    createFilter({
      id: "1",
      field: "duration",
      type,
      operator: ">",
      value,
    });

  it("should render a persisted millisecond threshold as seconds", () => {
    const result = normalizeFilters(
      [durationFilter("5000", COLUMN_TYPE.duration)],
      columns,
    );
    expect(result[0].value).toBe("5");
  });

  it("should convert even when the API omitted the type", () => {
    const result = normalizeFilters([durationFilter("5000", "")], columns);
    expect(result[0].type).toBe(COLUMN_TYPE.duration);
    expect(result[0].value).toBe("5");
  });

  it("should send a threshold entered in seconds as milliseconds", () => {
    const result = buildRuleFilters([
      durationFilter("5", COLUMN_TYPE.duration),
    ]);
    expect(result[0].value).toBe("5000");
  });

  // 16.1 * 1000 is 16100.000000000002 in floating point. Serialized raw, an "= 16.1s" filter never
  // matches a 16100ms trace and a ">= 16.1s" one excludes its own inclusive boundary — verified
  // against ClickHouse, which returns 0 rows for the unrounded value and 1 for the rounded one.
  it("should round the converted threshold so exact and inclusive boundaries match", () => {
    for (const [typed, expected] of [
      ["16.1", "16100"],
      ["32.3", "32300"],
      ["1.005", "1005"],
    ]) {
      expect(
        buildRuleFilters([durationFilter(typed, COLUMN_TYPE.duration)])[0]
          .value,
      ).toBe(expected);
    }
  });

  it("should keep sub-second thresholds expressible", () => {
    expect(
      buildRuleFilters([durationFilter("0.2", COLUMN_TYPE.duration)])[0].value,
    ).toBe("200");
    expect(
      normalizeFilters(
        [durationFilter("200", COLUMN_TYPE.duration)],
        columns,
      )[0].value,
    ).toBe("0.2");
  });

  // The threshold the user sees must survive repeated edit-and-save. The stored milliseconds may
  // shift by a float epsilon on the first save (16.1 stores as 16100.000000000002), which is why
  // this asserts on the displayed seconds rather than on what lands in the database.
  it("should not drift what the user sees when a rule is opened and saved repeatedly", () => {
    for (const typed of ["5", "0.1", "16.1", "1.005", "2.5", "30", "0.0005"]) {
      let displayed: Filter["value"] = typed;
      for (let i = 0; i < 3; i++) {
        const stored: Filter["value"] = buildRuleFilters([
          durationFilter(displayed, COLUMN_TYPE.duration),
        ])[0].value;
        displayed = normalizeFilters(
          [durationFilter(stored, COLUMN_TYPE.duration)],
          columns,
        )[0].value;
      }
      expect(displayed).toBe(typed);
    }
  });

  // Whitespace matters because Number("   ") is 0, so a blank-looking threshold would otherwise
  // be saved as "0" and match every trace. An outright empty value can only arrive from a
  // persisted rule, since buildRuleFilters drops it as incomplete before saving.
  it("should pass blank and non-numeric values through untouched", () => {
    for (const value of ["   ", "not-a-number"]) {
      expect(
        buildRuleFilters([durationFilter(value, COLUMN_TYPE.duration)])[0]
          .value,
      ).toBe(value);
      expect(
        normalizeFilters(
          [durationFilter(value, COLUMN_TYPE.duration)],
          columns,
        )[0].value,
      ).toBe(value);
    }
  });

  it("should render an empty persisted value as empty rather than zero", () => {
    expect(
      normalizeFilters([durationFilter("", COLUMN_TYPE.duration)], columns)[0]
        .value,
    ).toBe("");
  });
});

// getRule() delegates to buildRuleFilters, so these cover the payload the dialog actually sends:
// without them, deleting the conversion from the submit path would leave every test above passing.
describe("buildRuleFilters builds the rule payload", () => {
  it("should send a duration threshold typed in seconds as milliseconds", () => {
    const result = buildRuleFilters([
      createFilter({
        id: "1",
        field: "duration",
        type: COLUMN_TYPE.duration,
        operator: ">",
        value: "5",
      }),
    ]);

    expect(result).toHaveLength(1);
    expect(result[0].value).toBe("5000");
  });

  it("should drop incomplete rows before they reach the payload", () => {
    const result = buildRuleFilters([
      createFilter({
        id: "1",
        field: "duration",
        type: COLUMN_TYPE.duration,
        operator: ">",
        value: "",
      }),
      createFilter({
        id: "2",
        field: "duration",
        type: COLUMN_TYPE.duration,
        operator: ">",
        value: "5",
      }),
    ]);

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe("2");
  });

  it("should address a keyed input/output at its JSON column and still convert duration", () => {
    const result = buildRuleFilters([
      createFilter({
        id: "1",
        field: "output",
        type: COLUMN_TYPE.dictionary,
        operator: "contains",
        value: "hi",
        key: "answer",
      }),
      createFilter({
        id: "2",
        field: "duration",
        type: COLUMN_TYPE.duration,
        operator: ">",
        value: "0.2",
      }),
    ]);

    expect(result[0].field).toBe("output_json");
    expect(result[0].value).toBe("hi");
    expect(result[1].value).toBe("200");
  });

  it("should keep a keyless input/output filter, which the dictionary rules would reject", () => {
    const result = buildRuleFilters([
      createFilter({
        id: "1",
        field: "input",
        type: COLUMN_TYPE.dictionary,
        operator: "contains",
        value: "hello",
        key: "",
      }),
    ]);

    expect(result).toHaveLength(1);
    expect(result[0].field).toBe("input");
  });
});

describe("non-duration filters survive the round trip untouched", () => {
  // Thread rules persist time filters, so the duration conversion must not reach for
  // processFiltersArray: processTimeFilter would split "=" into two rows and snap the others to
  // minute boundaries, compounding on every edit-and-save.
  const roundTrip = (filter: Filter) =>
    buildRuleFilters(normalizeFilters([filter], columns));

  it("should keep an equals time filter as a single unchanged filter", () => {
    const filter = createFilter({
      id: "1",
      field: "created_at",
      type: COLUMN_TYPE.time,
      operator: "=",
      value: "2026-09-23T09:05:00.000Z",
    });

    const result = roundTrip(filter);
    expect(result).toHaveLength(1);
    expect(result[0].operator).toBe("=");
    expect(result[0].value).toBe("2026-09-23T09:05:00.000Z");
  });

  it("should not snap a greater-than time filter to a minute boundary", () => {
    const filter = createFilter({
      id: "1",
      field: "created_at",
      type: COLUMN_TYPE.time,
      operator: ">",
      value: "2026-09-23T09:05:12.454Z",
    });

    expect(roundTrip(filter)[0].value).toBe("2026-09-23T09:05:12.454Z");
  });

  it("should leave string, number and list filters alone", () => {
    const filters: Filter[] = [
      createFilter({
        id: "1",
        field: "name",
        type: COLUMN_TYPE.string,
        operator: "contains",
        value: "5000",
      }),
      createFilter({
        id: "2",
        field: "usage.total_tokens",
        type: COLUMN_TYPE.number,
        operator: ">",
        value: "5",
      }),
      createFilter({
        id: "3",
        field: "tags",
        type: COLUMN_TYPE.list,
        operator: "contains",
        value: "5",
      }),
    ];

    expect(buildRuleFilters(filters)).toEqual(filters);
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
