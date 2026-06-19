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
  it("returns null for a zero / non-finite duration or missing project", () => {
    expect(
      buildThreadDurationTarget({ ...base, duration: 0 } as Thread),
    ).toBeNull();
    expect(
      buildThreadDurationTarget({ ...base, duration: NaN } as Thread),
    ).toBeNull();
    expect(
      buildThreadDurationTarget({
        id: "t1",
        duration: 10,
      } as Thread),
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
  it("returns null when cost is 0, non-finite, or absent", () => {
    expect(
      buildThreadCostTarget({ ...base, total_estimated_cost: 0 } as Thread),
    ).toBeNull();
    expect(
      buildThreadCostTarget({
        ...base,
        total_estimated_cost: Infinity,
      } as Thread),
    ).toBeNull();
    expect(buildThreadCostTarget(base)).toBeNull();
  });
});
