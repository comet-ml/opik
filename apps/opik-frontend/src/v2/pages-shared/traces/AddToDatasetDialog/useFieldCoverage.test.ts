import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";

import { Span, Trace } from "@/types/traces";
import { MappingRow } from "./fieldMappingTypes";
import useFieldCoverage from "./useFieldCoverage";

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

const renderCoverage = ({
  fixedRows = FIXED_ROWS,
  customRows = [],
  managedRows = [],
  selectedEntities = [buildEntity()],
  sampleEntities = [],
}: {
  fixedRows?: MappingRow[];
  customRows?: MappingRow[];
  managedRows?: MappingRow[];
  selectedEntities?: Array<Trace | Span>;
  sampleEntities?: Array<Trace | Span>;
} = {}) =>
  renderHook(() =>
    useFieldCoverage({
      fixedRows,
      customRows,
      managedRows,
      selectedEntities,
      sampleEntities,
    }),
  );

describe("useFieldCoverage", () => {
  it("counts how many selected entities resolve each path", () => {
    const { result } = renderCoverage({
      customRows: [
        { id: "custom-1", name: "tone", kind: "custom", path: "input.tone" },
      ],
      selectedEntities: [
        buildEntity(),
        buildEntity({ id: "t2", input: { prompt: "second" } }),
        buildEntity({ id: "t3", input: { prompt: "third", tone: "formal" } }),
      ],
    });

    expect(result.current["custom-1"]).toEqual({ covered: 2, total: 3 });
    expect(result.current["input"]).toEqual({ covered: 3, total: 3 });
  });

  it("parses stringified input so nested paths still resolve", () => {
    const { result } = renderCoverage({
      customRows: [
        { id: "custom-1", name: "tone", kind: "custom", path: "input.tone" },
      ],
      selectedEntities: [
        buildEntity({
          input: JSON.stringify({ tone: "neutral" }) as unknown as object,
        }),
      ],
    });

    expect(result.current["custom-1"]).toEqual({ covered: 1, total: 1 });
  });

  it("drops nested paths when the value arrived truncated", () => {
    const { result } = renderCoverage({
      customRows: [
        { id: "custom-1", name: "tone", kind: "custom", path: "input.tone" },
      ],
      selectedEntities: [
        buildEntity(),
        buildEntity({
          id: "t2",
          input: '{"tone":"neu' as unknown as object,
        }),
      ],
    });

    expect(result.current["custom-1"]).toBeUndefined();
    expect(result.current["input"]).toEqual({ covered: 2, total: 2 });
  });

  it("drops a field the list response omitted entirely", () => {
    const { result } = renderCoverage({
      fixedRows: [],
      managedRows: [
        {
          id: "includeFeedbackScores",
          name: "feedback_scores",
          kind: "managed",
        },
      ],
      selectedEntities: [buildEntity(), buildEntity({ id: "t2" })],
      sampleEntities: [
        buildEntity({
          feedback_scores: [
            { name: "accuracy", value: 1, source: "ui" },
          ] as Trace["feedback_scores"],
        }),
      ],
    });

    expect(result.current["includeFeedbackScores"]).toBeUndefined();
  });

  it("counts a managed field that is genuinely absent everywhere", () => {
    const { result } = renderCoverage({
      fixedRows: [],
      managedRows: [{ id: "includeTags", name: "tags", kind: "managed" }],
      selectedEntities: [
        buildEntity({ tags: ["a"] }),
        buildEntity({ id: "t2", tags: [] }),
      ],
    });

    expect(result.current["includeTags"]).toEqual({ covered: 1, total: 2 });
  });

  it("never reports coverage for nested spans", () => {
    const { result } = renderCoverage({
      fixedRows: [],
      managedRows: [{ id: "includeSpans", name: "spans", kind: "managed" }],
    });

    expect(result.current["includeSpans"]).toBeUndefined();
  });

  it("returns nothing when no entities are selected", () => {
    const { result } = renderCoverage({ selectedEntities: [] });

    expect(result.current).toEqual({});
  });
});
