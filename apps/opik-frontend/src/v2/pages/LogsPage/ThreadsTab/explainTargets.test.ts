import { describe, it, expect } from "vitest";
import {
  buildThreadCostTarget,
  buildThreadDurationTarget,
} from "./explainTargets";
import { Thread } from "@/types/traces";

const base = {
  id: "t1",
  project_id: "p1",
  number_of_messages: 6,
} as Thread;

describe("buildThreadDurationTarget", () => {
  it("builds a thread.duration target with the message count", () => {
    expect(
      buildThreadDurationTarget({ ...base, duration: 4200 } as Thread),
    ).toEqual({
      kind: "thread.duration",
      entityId: "t1",
      projectId: "p1",
      payload: { duration: 4200, number_of_messages: 6 },
    });
  });
  it("omits number_of_messages when not a finite number", () => {
    const row = { id: "t1", project_id: "p1", duration: 10 } as Thread;
    expect(buildThreadDurationTarget(row)).toEqual({
      kind: "thread.duration",
      entityId: "t1",
      projectId: "p1",
      payload: { duration: 10 },
    });
  });
  it("still builds a target for a zero / N/A duration (the value is not gated)", () => {
    expect(
      buildThreadDurationTarget({ ...base, duration: 0 } as Thread),
    ).toEqual({
      kind: "thread.duration",
      entityId: "t1",
      projectId: "p1",
      payload: { duration: 0, number_of_messages: 6 },
    });
    // N/A duration → explicit `null` (survives JSON.stringify; `undefined`
    // would be stripped and rejected by the backend payload validator).
    expect(
      buildThreadDurationTarget({
        ...base,
        duration: undefined,
      } as unknown as Thread),
    ).toEqual({
      kind: "thread.duration",
      entityId: "t1",
      projectId: "p1",
      payload: { duration: null, number_of_messages: 6 },
    });
  });
  it("returns null without project_id", () => {
    expect(
      buildThreadDurationTarget({ id: "t1", duration: 10 } as Thread),
    ).toBeNull();
  });
});

describe("buildThreadCostTarget", () => {
  it("builds a thread.cost target with the message count", () => {
    expect(
      buildThreadCostTarget({ ...base, total_estimated_cost: 0.42 } as Thread),
    ).toEqual({
      kind: "thread.cost",
      entityId: "t1",
      projectId: "p1",
      payload: { total_estimated_cost: 0.42, number_of_messages: 6 },
    });
  });
  it("does not leak model/provider/span_type (thread cost payload is minimal)", () => {
    const target = buildThreadCostTarget({
      ...base,
      total_estimated_cost: 0.42,
    } as Thread);
    expect(Object.keys(target?.payload ?? {}).sort()).toEqual([
      "number_of_messages",
      "total_estimated_cost",
    ]);
  });
  it("still builds a target for a zero / N/A cost (the value is not gated)", () => {
    expect(
      buildThreadCostTarget({ ...base, total_estimated_cost: 0 } as Thread),
    ).toEqual({
      kind: "thread.cost",
      entityId: "t1",
      projectId: "p1",
      payload: { total_estimated_cost: 0, number_of_messages: 6 },
    });
    // An absent cost is sent as explicit `null`, not `undefined`: the latter is
    // stripped by JSON.stringify at the console's HTTP boundary, leaving the
    // backend with no `total_estimated_cost` key ("Invalid payload").
    expect(buildThreadCostTarget(base)).toEqual({
      kind: "thread.cost",
      entityId: "t1",
      projectId: "p1",
      payload: { total_estimated_cost: null, number_of_messages: 6 },
    });
  });
  it("returns null without project_id", () => {
    expect(
      buildThreadCostTarget({ id: "t1", total_estimated_cost: 1 } as Thread),
    ).toBeNull();
  });
});
