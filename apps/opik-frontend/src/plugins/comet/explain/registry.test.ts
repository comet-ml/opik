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
  it("registers a label for every trace/span/thread kind", () => {
    expect(getExplainConfig("trace.error")?.label).toBe("Explain error");
    expect(getExplainConfig("trace.cost")?.label).toBe("Explain cost");
    expect(getExplainConfig("trace.duration")?.label).toBe("Explain duration");
    expect(getExplainConfig("span.error")?.label).toBe("Explain error");
    expect(getExplainConfig("span.cost")?.label).toBe("Explain cost");
    expect(getExplainConfig("span.duration")?.label).toBe("Explain duration");
    expect(getExplainConfig("thread.duration")?.label).toBe("Explain duration");
    expect(getExplainConfig("thread.cost")?.label).toBe("Explain cost");
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
    expect(getExplainConfig("span.error")?.question(t("span.error"))).toContain(
      "ValueError",
    );
    expect(getExplainConfig("span.cost")?.question(t("span.cost"))).toBe(
      "Explain this cost",
    );
    expect(
      getExplainConfig("span.duration")?.question(t("span.duration")),
    ).toBe("Explain this duration");
    expect(
      getExplainConfig("thread.duration")?.question(t("thread.duration")),
    ).toBe("Explain this duration");
    expect(getExplainConfig("thread.cost")?.question(t("thread.cost"))).toBe(
      "Explain this cost",
    );
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
