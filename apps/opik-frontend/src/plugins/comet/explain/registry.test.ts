import { describe, it, expect } from "vitest";
import { getExplainConfig } from "./registry";
import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";

const t = (kind: ExplainKind): ExplainTarget => ({
  kind,
  entityId: "e1",
  projectId: "p1",
  payload: { exception_type: "ValueError" },
});

describe("AI_EXPLAIN_REGISTRY", () => {
  it("registers all three kinds with labels", () => {
    expect(getExplainConfig("trace.error")?.label).toBe("Explain error");
    expect(getExplainConfig("trace.cost")?.label).toBe("Explain cost");
    expect(getExplainConfig("trace.duration")?.label).toBe("Explain duration");
  });
  it("produces a non-empty seed question per kind", () => {
    expect(
      getExplainConfig("trace.error")?.question(t("trace.error")),
    ).toContain("ValueError");
    expect(getExplainConfig("trace.cost")?.question(t("trace.cost"))).toBe(
      "Explain this cost",
    );
    expect(
      getExplainConfig("trace.duration")?.question(t("trace.duration")),
    ).toBe("Explain this duration");
  });
  it("falls back to a generic error question when exception_type is absent", () => {
    expect(
      getExplainConfig("trace.error")?.question({
        ...t("trace.error"),
        payload: {},
      }),
    ).toBe("Explain this error");
  });
});
