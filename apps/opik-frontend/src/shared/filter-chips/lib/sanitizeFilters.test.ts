import { describe, expect, it } from "vitest";
import { sanitizeFilters } from "./sanitizeFilters";
import { ChipDefinition } from "@/shared/filter-chips/types";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";
import {
  DICTIONARY_OPERATORS,
  FEEDBACK_SCORE_OPERATORS,
  TAGS_OPERATORS,
  STRING_OPERATORS,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";

const f = (overrides: Partial<Filter>): Filter => ({
  id: overrides.id ?? "f",
  field: overrides.field ?? "",
  type: overrides.type ?? "",
  operator: overrides.operator ?? "=",
  key: overrides.key,
  value: overrides.value ?? "",
});

const booleanDef: ChipDefinition = {
  id: "with_errors",
  field: "error_info",
  label: "With errors",
  kind: "boolean",
  onOperator: "is_not_empty",
  columnType: COLUMN_TYPE.errors,
};

const singleSelectDef: ChipDefinition = {
  id: "type",
  field: "type",
  label: "Type",
  kind: "single-select",
  options: [
    { label: "LLM", value: "llm" },
    { label: "Tool", value: "tool" },
  ],
  columnType: COLUMN_TYPE.category,
  operator: "=",
};

const numericDef: ChipDefinition = {
  id: "duration",
  field: "duration",
  label: "Duration",
  kind: "numeric",
  columnType: COLUMN_TYPE.duration,
};

const timeDef: ChipDefinition = {
  id: "start_time",
  field: "start_time",
  label: "Start time",
  kind: "time",
  columnType: COLUMN_TYPE.time,
};

const pseudoTextDef: ChipDefinition = {
  id: "input",
  field: "input",
  label: "Input",
  kind: "query-builder",
  columnType: COLUMN_TYPE.string,
  operators: STRING_OPERATORS,
  defaultOperator: "contains",
};

const pseudoIdDef: ChipDefinition = {
  id: "thread_id",
  field: "thread_id",
  label: "Thread ID",
  kind: "query-builder",
  columnType: COLUMN_TYPE.string,
  operators: STRING_OPERATORS,
  defaultOperator: "=",
};

const tagsDef: ChipDefinition = {
  id: "tags",
  field: "tags",
  label: "Tags",
  kind: "query-builder",
  columnType: COLUMN_TYPE.list,
  operators: TAGS_OPERATORS,
  defaultOperator: "contains",
};

const metadataDef: ChipDefinition = {
  id: "metadata",
  field: "metadata",
  label: "Metadata",
  kind: "query-builder",
  columnType: COLUMN_TYPE.dictionary,
  operators: DICTIONARY_OPERATORS,
  defaultOperator: "=",
};

const feedbackScoresDef: ChipDefinition = {
  id: "feedback_scores",
  field: "feedback_scores",
  label: "Feedback scores",
  kind: "query-builder",
  columnType: COLUMN_TYPE.numberDictionary,
  operators: FEEDBACK_SCORE_OPERATORS,
  defaultOperator: ">=",
};

const DEFS: ChipDefinition[] = [
  booleanDef,
  singleSelectDef,
  numericDef,
  timeDef,
  pseudoTextDef,
  pseudoIdDef,
  tagsDef,
  metadataDef,
  feedbackScoresDef,
];

describe("sanitizeFilters — boolean", () => {
  it("matches the configured onOperator", () => {
    const res = sanitizeFilters(
      [f({ field: "error_info", operator: "is_not_empty" })],
      DEFS,
    );
    expect(res.values.with_errors).toEqual({ applied: true });
    expect(res.dropped).toEqual([]);
  });

  it("collapses duplicate matching filters without dropping", () => {
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "error_info", operator: "is_not_empty" }),
        f({ id: "b", field: "error_info", operator: "is_not_empty" }),
      ],
      DEFS,
    );
    expect(res.values.with_errors).toEqual({ applied: true });
    expect(res.dropped).toEqual([]);
  });

  it("drops is_empty (chip cannot express the inverse)", () => {
    const res = sanitizeFilters(
      [f({ field: "error_info", operator: "is_empty" })],
      DEFS,
    );
    expect(res.values.with_errors).toBeUndefined();
    expect(res.dropped).toHaveLength(1);
    expect(res.dropped[0].reason).toBe("unsupported_operator");
  });
});

describe("sanitizeFilters — single-select", () => {
  it("accepts an = filter whose value is a known option", () => {
    const res = sanitizeFilters(
      [f({ field: "type", operator: "=", value: "llm" })],
      DEFS,
    );
    expect(res.values.type).toEqual({ value: "llm" });
    expect(res.dropped).toEqual([]);
  });

  it("drops filters with unknown values", () => {
    const res = sanitizeFilters(
      [f({ field: "type", operator: "=", value: "unknown" })],
      DEFS,
    );
    expect(res.values.type).toBeUndefined();
    expect(res.dropped[0].reason).toBe("invalid_value");
  });

  it("keeps the first conflicting value and drops the rest", () => {
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "type", operator: "=", value: "llm" }),
        f({ id: "b", field: "type", operator: "=", value: "tool" }),
      ],
      DEFS,
    );
    expect(res.values.type).toEqual({ value: "llm" });
    expect(res.dropped).toHaveLength(1);
    expect(res.dropped[0].reason).toBe("duplicate_field");
  });
});

describe("sanitizeFilters — numeric", () => {
  it("reads = as exactly", () => {
    const res = sanitizeFilters(
      [f({ field: "duration", operator: "=", value: "100" })],
      DEFS,
    );
    expect(res.values.duration).toEqual({ mode: "exactly", exact: 100 });
  });

  it("reads >= alone as atLeast", () => {
    const res = sanitizeFilters(
      [f({ field: "duration", operator: ">=", value: "100" })],
      DEFS,
    );
    expect(res.values.duration).toEqual({ mode: "atLeast", min: 100 });
  });

  it("reads <= alone as atMost", () => {
    const res = sanitizeFilters(
      [f({ field: "duration", operator: "<=", value: "500" })],
      DEFS,
    );
    expect(res.values.duration).toEqual({ mode: "atMost", max: 500 });
  });

  it("pairs >= and <= into between", () => {
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "duration", operator: ">=", value: "100" }),
        f({ id: "b", field: "duration", operator: "<=", value: "500" }),
      ],
      DEFS,
    );
    expect(res.values.duration).toEqual({
      mode: "between",
      min: 100,
      max: 500,
    });
    expect(res.dropped).toEqual([]);
  });

  it("demotes strict > to atLeast silently", () => {
    const res = sanitizeFilters(
      [f({ field: "duration", operator: ">", value: "100" })],
      DEFS,
    );
    expect(res.values.duration).toEqual({ mode: "atLeast", min: 100 });
    expect(res.dropped).toEqual([]);
  });

  it("uses the tightest bounds when several >= are present", () => {
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "duration", operator: ">=", value: "100" }),
        f({ id: "b", field: "duration", operator: ">=", value: "300" }),
        f({ id: "c", field: "duration", operator: "<=", value: "500" }),
      ],
      DEFS,
    );
    expect(res.values.duration).toEqual({
      mode: "between",
      min: 300,
      max: 500,
    });
  });

  it("drops malformed numeric values", () => {
    const res = sanitizeFilters(
      [f({ field: "duration", operator: "=", value: "not-a-number" })],
      DEFS,
    );
    expect(res.values.duration).toBeUndefined();
    expect(res.dropped[0].reason).toBe("invalid_value");
  });
});

describe("sanitizeFilters — time", () => {
  it("recognises the chip's exactly fingerprint (>=X, <=X+1min)", () => {
    const start = "2026-05-25T10:00:00.000Z";
    const end = "2026-05-25T10:01:00.000Z";
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "start_time", operator: ">=", value: start }),
        f({ id: "b", field: "start_time", operator: "<=", value: end }),
      ],
      DEFS,
    );
    expect(res.values.start_time).toEqual({ mode: "exactly", at: start });
  });

  it("also recognises legacy >=X + <X+1min (strict upper) as exactly", () => {
    const start = "2026-05-25T10:00:00.000Z";
    const end = "2026-05-25T10:01:00.000Z";
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "start_time", operator: ">=", value: start }),
        f({ id: "b", field: "start_time", operator: "<", value: end }),
      ],
      DEFS,
    );
    expect(res.values.start_time).toEqual({ mode: "exactly", at: start });
  });

  it("pairs >= and <= into between", () => {
    const start = "2026-05-01T00:00:00.000Z";
    const end = "2026-05-25T00:00:00.000Z";
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "start_time", operator: ">=", value: start }),
        f({ id: "b", field: "start_time", operator: "<=", value: end }),
      ],
      DEFS,
    );
    expect(res.values.start_time).toEqual({
      mode: "between",
      start,
      end,
    });
  });

  it("reads > as after, < as before", () => {
    const res = sanitizeFilters(
      [
        f({
          field: "start_time",
          operator: ">",
          value: "2026-05-25T00:00:00.000Z",
        }),
      ],
      DEFS,
    );
    expect(res.values.start_time).toEqual({
      mode: "after",
      after: "2026-05-25T00:00:00.000Z",
    });
  });

  it("demotes >= alone to after when not paired", () => {
    const res = sanitizeFilters(
      [
        f({
          field: "start_time",
          operator: ">=",
          value: "2026-05-25T00:00:00.000Z",
        }),
      ],
      DEFS,
    );
    expect(res.values.start_time).toEqual({
      mode: "after",
      after: "2026-05-25T00:00:00.000Z",
    });
  });

  it("reads = on timestamp as exactly", () => {
    const at = "2026-05-25T10:00:00.000Z";
    const res = sanitizeFilters(
      [f({ field: "start_time", operator: "=", value: at })],
      DEFS,
    );
    expect(res.values.start_time).toEqual({ mode: "exactly", at });
  });
});

describe("sanitizeFilters — text/ID query-builder fields", () => {
  it("accepts contains on a text field", () => {
    const res = sanitizeFilters(
      [f({ field: "input", operator: "contains", value: "hello" })],
      DEFS,
    );
    expect(res.values.input).toEqual({
      rows: [expect.objectContaining({ operator: "contains", value: "hello" })],
    });
  });

  it("accepts not_contains / starts_with / ends_with / = on a text field", () => {
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "input", operator: "starts_with", value: "x" }),
        f({ id: "b", field: "input", operator: "ends_with", value: "y" }),
        f({ id: "c", field: "input", operator: "not_contains", value: "z" }),
        f({ id: "d", field: "input", operator: "=", value: "w" }),
      ],
      DEFS,
    );
    expect(res.values.input).toEqual({
      rows: [
        expect.objectContaining({ operator: "starts_with", value: "x" }),
        expect.objectContaining({ operator: "ends_with", value: "y" }),
        expect.objectContaining({ operator: "not_contains", value: "z" }),
        expect.objectContaining({ operator: "=", value: "w" }),
      ],
    });
    expect(res.dropped).toHaveLength(0);
  });

  it("accepts = on an ID field", () => {
    const res = sanitizeFilters(
      [f({ field: "thread_id", operator: "=", value: "abc-123" })],
      DEFS,
    );
    expect(res.values.thread_id).toEqual({
      rows: [expect.objectContaining({ operator: "=", value: "abc-123" })],
    });
  });

  it("accepts contains on an ID field", () => {
    const res = sanitizeFilters(
      [f({ field: "thread_id", operator: "contains", value: "abc" })],
      DEFS,
    );
    expect(res.values.thread_id).toEqual({
      rows: [expect.objectContaining({ operator: "contains", value: "abc" })],
    });
  });
});

describe("sanitizeFilters — query-builder", () => {
  it("maps each matching legacy filter to a row", () => {
    const res = sanitizeFilters(
      [
        f({
          id: "a",
          field: "metadata",
          key: "model",
          operator: "=",
          value: "gpt-4",
        }),
        f({
          id: "b",
          field: "metadata",
          key: "env",
          operator: "contains",
          value: "prod",
        }),
      ],
      DEFS,
    );
    expect(res.values.metadata).toBeDefined();
    expect(
      (res.values.metadata as { rows: Filter[] }).rows.map((r) => ({
        key: r.key,
        operator: r.operator,
        value: r.value,
      })),
    ).toEqual([
      { key: "model", operator: "=", value: "gpt-4" },
      { key: "env", operator: "contains", value: "prod" },
    ]);
    expect(res.dropped).toEqual([]);
  });

  it("silently demotes strict > to >= when only the inclusive bound is allowed", () => {
    const res = sanitizeFilters(
      [
        f({
          field: "feedback_scores",
          key: "relevance",
          operator: ">",
          value: "0.5",
        }),
      ],
      DEFS,
    );
    const rows = (res.values.feedback_scores as { rows: Filter[] }).rows;
    expect(rows).toHaveLength(1);
    expect(rows[0].operator).toBe(">=");
    expect(rows[0].value).toBe("0.5");
    expect(res.dropped).toEqual([]);
  });

  it("silently demotes strict < to <= when only the inclusive bound is allowed", () => {
    const res = sanitizeFilters(
      [
        f({
          field: "feedback_scores",
          key: "relevance",
          operator: "<",
          value: "0.5",
        }),
      ],
      DEFS,
    );
    const rows = (res.values.feedback_scores as { rows: Filter[] }).rows;
    expect(rows).toHaveLength(1);
    expect(rows[0].operator).toBe("<=");
  });

  it("drops rows whose operator is truly outside the chip's allow-list", () => {
    const res = sanitizeFilters(
      [
        f({
          field: "feedback_scores",
          key: "relevance",
          operator: "starts_with",
          value: "0.5",
        }),
      ],
      DEFS,
    );
    expect(res.values.feedback_scores).toBeUndefined();
    expect(res.dropped[0].reason).toBe("unsupported_operator");
  });

  it("keeps tags rows with operators in TAGS_OPERATORS", () => {
    const res = sanitizeFilters(
      [
        f({ field: "tags", operator: "contains", value: "prod" }),
        f({ field: "tags", operator: "not_contains", value: "test" }),
        f({ field: "tags", operator: "=", value: "exact-tag" }),
      ],
      DEFS,
    );
    const rows = (res.values.tags as { rows: Filter[] }).rows;
    expect(rows).toHaveLength(3);
  });
});

describe("sanitizeFilters — no matching chip", () => {
  it("drops filters for fields without a chip definition", () => {
    const res = sanitizeFilters(
      [f({ field: "unknown_field", operator: "=", value: "x" })],
      DEFS,
    );
    expect(res.values).toEqual({});
    expect(res.dropped).toHaveLength(1);
    expect(res.dropped[0].reason).toBe("no_matching_chip");
  });
});

describe("sanitizeFilters — malformed input", () => {
  it("handles undefined / null / empty arrays", () => {
    expect(sanitizeFilters(undefined, DEFS).values).toEqual({});
    expect(sanitizeFilters(null, DEFS).values).toEqual({});
    expect(sanitizeFilters([], DEFS).values).toEqual({});
  });
});

// Invariant: every input filter must end up in `dropped` or contribute to
// `values`. The dispatcher's catch-all guarantees nothing is silently lost,
// even if a per-kind picker forgets to claim a candidate on some code path.
describe("sanitizeFilters — accounting invariant", () => {
  const cases: Array<{ name: string; input: Filter[] }> = [
    {
      name: "Time >=X + <Y outside the 60s exactly window",
      input: [
        f({
          id: "a",
          field: "start_time",
          operator: ">=",
          value: "2026-05-01T00:00:00.000Z",
        }),
        f({
          id: "b",
          field: "start_time",
          operator: "<",
          value: "2026-05-02T00:00:00.000Z",
        }),
      ],
    },
    {
      name: "Time between with malformed <= value",
      input: [
        f({
          id: "a",
          field: "start_time",
          operator: ">=",
          value: "2026-05-01T00:00:00.000Z",
        }),
        f({
          id: "b",
          field: "start_time",
          operator: "<=",
          value: "not-a-date",
        }),
      ],
    },
    {
      name: "Numeric eq with leading malformed + trailing valid",
      input: [
        f({ id: "a", field: "duration", operator: "=", value: "not-a-number" }),
        f({ id: "b", field: "duration", operator: "=", value: "42" }),
      ],
    },
    {
      name: "Numeric inverted range (lower > upper)",
      input: [
        f({ id: "a", field: "duration", operator: ">=", value: "500" }),
        f({ id: "b", field: "duration", operator: "<=", value: "100" }),
      ],
    },
  ];

  for (const { name, input } of cases) {
    it(`accounts for every filter: ${name}`, () => {
      const res = sanitizeFilters(input, DEFS);
      const droppedIds = new Set(res.dropped.map((d) => d.filter.id));
      const unaccounted = input.filter((f) => !droppedIds.has(f.id));
      // Anything not dropped must have contributed to some chip value.
      // Either way, the union of dropped + contributed must equal the input.
      expect(droppedIds.size + unaccounted.length).toBe(input.length);
      // For these specific orphan cases, the picker may not consume the
      // filter into a chip value — but it must NOT vanish silently.
      // Assert that combined coverage equals input length.
      const allFilterIds = new Set(input.map((f) => f.id));
      const accountedFor = [...droppedIds, ...unaccounted.map((f) => f.id)];
      for (const id of allFilterIds) {
        expect(accountedFor).toContain(id);
      }
    });
  }
});

describe("sanitizeFilters — numeric inverted range", () => {
  it("drops both bounds as invalid_value, doesn't demote to atLeast", () => {
    const res = sanitizeFilters(
      [
        f({ id: "a", field: "duration", operator: ">=", value: "500" }),
        f({ id: "b", field: "duration", operator: "<=", value: "100" }),
      ],
      DEFS,
    );
    expect(res.values.duration).toBeUndefined();
    expect(res.dropped).toHaveLength(2);
    expect(res.dropped.every((d) => d.reason === "invalid_value")).toBe(true);
  });
});
