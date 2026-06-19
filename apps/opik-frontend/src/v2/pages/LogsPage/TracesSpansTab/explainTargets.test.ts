import { describe, it, expect } from "vitest";
import {
  buildCostTarget,
  buildDurationTarget,
  buildErrorTarget,
  buildSpanCostTarget,
  buildSpanDurationTarget,
  buildSpanErrorTarget,
  type ExplainableRow,
} from "./explainTargets";

const base = { id: "e1", project_id: "p1" } as ExplainableRow;

describe("buildErrorTarget", () => {
  it("builds a trace.error target from error_info", () => {
    const row = {
      ...base,
      error_info: {
        exception_type: "ValueError",
        message: "m",
        traceback: "tb",
      },
    } as ExplainableRow;
    expect(buildErrorTarget(row)).toEqual({
      kind: "trace.error",
      entityId: "e1",
      projectId: "p1",
      payload: { exception_type: "ValueError", message: "m", traceback: "tb" },
    });
  });
  it("returns null without error_info", () => {
    expect(buildErrorTarget(base)).toBeNull();
  });
  it("returns null without project_id", () => {
    const row = {
      id: "e1",
      error_info: { exception_type: "X", traceback: "" },
    } as ExplainableRow;
    expect(buildErrorTarget(row)).toBeNull();
  });
});

describe("buildDurationTarget", () => {
  it("builds a trace.duration target", () => {
    const row = { ...base, duration: 1234 } as ExplainableRow;
    expect(buildDurationTarget(row)).toEqual({
      kind: "trace.duration",
      entityId: "e1",
      projectId: "p1",
      payload: { duration: 1234 },
    });
  });
  it("returns null when duration is not a number", () => {
    expect(buildDurationTarget(base)).toBeNull();
  });
  it("returns null for a zero / non-finite duration (no value to explain)", () => {
    expect(
      buildDurationTarget({ ...base, duration: 0 } as ExplainableRow),
    ).toBeNull();
    expect(
      buildDurationTarget({ ...base, duration: NaN } as ExplainableRow),
    ).toBeNull();
    expect(
      buildDurationTarget({ ...base, duration: Infinity } as ExplainableRow),
    ).toBeNull();
  });
});

describe("buildCostTarget", () => {
  it("builds a trace.cost target with span model/provider/type", () => {
    const row = {
      ...base,
      total_estimated_cost: 0.0021,
      model: "gpt-4o",
      provider: "openai",
      type: "llm",
    } as ExplainableRow;
    expect(buildCostTarget(row)).toEqual({
      kind: "trace.cost",
      entityId: "e1",
      projectId: "p1",
      payload: {
        total_estimated_cost: 0.0021,
        model: "gpt-4o",
        provider: "openai",
        span_type: "llm",
      },
    });
  });
  it("omits absent span fields for a trace row", () => {
    const row = { ...base, total_estimated_cost: 0.5 } as ExplainableRow;
    expect(buildCostTarget(row)).toEqual({
      kind: "trace.cost",
      entityId: "e1",
      projectId: "p1",
      payload: { total_estimated_cost: 0.5 },
    });
  });
  it("returns null when cost is 0, non-finite, or absent", () => {
    expect(
      buildCostTarget({ ...base, total_estimated_cost: 0 } as ExplainableRow),
    ).toBeNull();
    expect(
      buildCostTarget({ ...base, total_estimated_cost: NaN } as ExplainableRow),
    ).toBeNull();
    expect(
      buildCostTarget({
        ...base,
        total_estimated_cost: Infinity,
      } as ExplainableRow),
    ).toBeNull();
    expect(buildCostTarget(base)).toBeNull();
  });
});

// The span builders share the trace builders' extraction and visibility rules;
// these tests pin the entity-specific `kind` and that span cost enrichment
// (model/provider/span_type) is preserved on the span variant.
describe("span builders", () => {
  it("buildSpanErrorTarget emits span.error with the same payload shape", () => {
    const row = {
      ...base,
      error_info: {
        exception_type: "ValueError",
        message: "m",
        traceback: "tb",
      },
    } as ExplainableRow;
    expect(buildSpanErrorTarget(row)).toEqual({
      kind: "span.error",
      entityId: "e1",
      projectId: "p1",
      payload: { exception_type: "ValueError", message: "m", traceback: "tb" },
    });
    expect(buildSpanErrorTarget(base)).toBeNull();
  });

  it("buildSpanDurationTarget emits span.duration", () => {
    expect(
      buildSpanDurationTarget({ ...base, duration: 1234 } as ExplainableRow),
    ).toEqual({
      kind: "span.duration",
      entityId: "e1",
      projectId: "p1",
      payload: { duration: 1234 },
    });
    expect(
      buildSpanDurationTarget({ ...base, duration: 0 } as ExplainableRow),
    ).toBeNull();
  });

  it("buildSpanCostTarget emits span.cost and keeps model/provider/span_type", () => {
    const row = {
      ...base,
      total_estimated_cost: 0.0021,
      model: "gpt-4o",
      provider: "openai",
      type: "llm",
    } as ExplainableRow;
    expect(buildSpanCostTarget(row)).toEqual({
      kind: "span.cost",
      entityId: "e1",
      projectId: "p1",
      payload: {
        total_estimated_cost: 0.0021,
        model: "gpt-4o",
        provider: "openai",
        span_type: "llm",
      },
    });
    expect(
      buildSpanCostTarget({
        ...base,
        total_estimated_cost: 0,
      } as ExplainableRow),
    ).toBeNull();
  });
});
