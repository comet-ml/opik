import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";

import { Span, Trace } from "@/types/traces";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { MappingRow } from "./fieldMappingTypes";
import { FieldRowError } from "./useFieldMappings";
import useMappingPreview from "./useMappingPreview";

const FIXED_ROWS: MappingRow[] = [
  { id: "input", name: "input", kind: "fixed", path: "input" },
  {
    id: "expected_output",
    name: "expected_output",
    kind: "fixed",
    path: "output",
  },
];

const buildEntity = (overrides: Partial<Trace> = {}) =>
  ({
    id: "trace-1",
    input: { prompt: "hello", tone: "neutral" },
    output: { answer: "hi" },
    ...overrides,
  }) as Trace;

const renderPreview = ({
  fixedRows = FIXED_ROWS,
  customRows = [],
  managedRows = [],
  rowErrors = {},
  entities = [buildEntity()],
}: {
  fixedRows?: MappingRow[];
  customRows?: MappingRow[];
  managedRows?: MappingRow[];
  rowErrors?: Record<string, FieldRowError>;
  entities?: Array<Trace | Span>;
} = {}) =>
  renderHook(() =>
    useMappingPreview({
      fixedRows,
      customRows,
      managedRows,
      rowErrors,
      entities,
    }),
  );

describe("useMappingPreview", () => {
  it("lists fixed, custom and managed columns in that order", () => {
    const { result } = renderPreview({
      customRows: [
        { id: "custom-1", name: "tone", kind: "custom", path: "input.tone" },
      ],
      managedRows: [
        { id: "includeTags", name: "tags", kind: "managed" },
        { id: "includeSpans", name: "spans", kind: "managed" },
      ],
    });

    expect(result.current.columns).toEqual([
      "input",
      "expected_output",
      "tone",
      "tags",
      "spans",
    ]);
  });

  it("resolves mapped paths against every sampled entity", () => {
    const { result } = renderPreview({
      customRows: [
        { id: "custom-1", name: "tone", kind: "custom", path: "input.tone" },
      ],
      entities: [
        buildEntity(),
        buildEntity({ id: "trace-2", input: { prompt: "second" } }),
      ],
    });

    expect(result.current.rows.map((row) => row.cells.tone)).toEqual([
      { kind: "value", text: "neutral" },
      { kind: "empty" },
    ]);
  });

  it("resolves bracket paths", () => {
    const { result } = renderPreview({
      customRows: [
        {
          id: "custom-1",
          name: "first",
          kind: "custom",
          path: "input.choices[0].message",
        },
      ],
      entities: [
        buildEntity({
          input: { choices: [{ message: "picked" }] },
        } as Partial<Trace>),
      ],
    });

    expect(result.current.rows[0].cells.first).toEqual({
      kind: "value",
      text: "picked",
    });
  });

  it("stringifies non string values", () => {
    const { result } = renderPreview();

    expect(result.current.rows[0].cells.input).toEqual({
      kind: "value",
      text: '{"prompt":"hello","tone":"neutral"}',
    });
  });

  it("treats null and empty arrays as empty but keeps empty strings and objects", () => {
    const { result } = renderPreview({
      fixedRows: [],
      customRows: [
        { id: "a", name: "a", kind: "custom", path: "input.nullish" },
        { id: "b", name: "b", kind: "custom", path: "input.list" },
        { id: "c", name: "c", kind: "custom", path: "input.text" },
        { id: "d", name: "d", kind: "custom", path: "input.obj" },
      ],
      entities: [
        buildEntity({
          input: { nullish: null, list: [], text: "", obj: {} },
        } as Partial<Trace>),
      ],
    });

    expect(result.current.rows[0].cells).toEqual({
      a: { kind: "empty" },
      b: { kind: "empty" },
      c: { kind: "value", text: "" },
      d: { kind: "value", text: "{}" },
    });
  });

  it("skips custom rows with a name error or without a path", () => {
    const { result } = renderPreview({
      customRows: [
        { id: "custom-1", name: "input", kind: "custom", path: "input.tone" },
        { id: "custom-2", name: "loose", kind: "custom" },
      ],
      rowErrors: { "custom-1": "duplicate" },
    });

    expect(result.current.columns).toEqual(["input", "expected_output"]);
  });

  it("reshapes feedback scores and comments the way the backend does", () => {
    const { result } = renderPreview({
      fixedRows: [],
      managedRows: [
        {
          id: "includeFeedbackScores",
          name: "feedback_scores",
          kind: "managed",
        },
        { id: "includeComments", name: "comments", kind: "managed" },
      ],
      entities: [
        buildEntity({
          feedback_scores: [
            {
              name: "accuracy",
              value: 1,
              source: FEEDBACK_SCORE_TYPE.ui,
              last_updated_at: "2026-01-01",
            },
          ],
          comments: [
            {
              id: "comment-1",
              text: "looks good",
              created_at: "2026-01-01",
              last_updated_at: "2026-01-01",
              created_by: "me",
              last_updated_by: "me",
            },
          ],
        }),
      ],
    });

    expect(result.current.rows[0].cells.feedback_scores).toEqual({
      kind: "value",
      text: '[{"name":"accuracy","value":1,"source":"ui"}]',
    });
    expect(result.current.rows[0].cells.comments).toEqual({
      kind: "value",
      text: '[{"id":"comment-1","text":"looks good"}]',
    });
  });

  it("marks spans as deferred", () => {
    const { result } = renderPreview({
      fixedRows: [],
      managedRows: [{ id: "includeSpans", name: "spans", kind: "managed" }],
    });

    expect(result.current.rows[0].cells.spans).toEqual({ kind: "deferred" });
  });

  it("treats an empty usage object as empty", () => {
    const { result } = renderPreview({
      fixedRows: [],
      managedRows: [{ id: "includeUsage", name: "usage", kind: "managed" }],
      entities: [buildEntity({ usage: {} as Trace["usage"] })],
    });

    expect(result.current.rows[0].cells.usage).toEqual({ kind: "empty" });
  });
});
