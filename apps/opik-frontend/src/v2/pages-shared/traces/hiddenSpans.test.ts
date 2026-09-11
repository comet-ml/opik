import { describe, it, expect } from "vitest";
import { Span, SPAN_TYPE } from "@/types/traces";
import { hasHiddenSpans, excludeHiddenSpans } from "./hiddenSpans";
import { isSpanHiddenByDefault } from "./spanVisibility";

// Matches the SDK-written metadata for a hidden-by-default span (is_internal flag).
const METADATA_OPIK_KEY = "_opik";

const makeSpan = (overrides: Partial<Span> & { id: string }): Span => ({
  name: overrides.id,
  input: {},
  output: {},
  start_time: "2024-01-01T00:00:00Z",
  end_time: "2024-01-01T00:00:01Z",
  duration: 1000,
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:00:00Z",
  metadata: {},
  comments: [],
  tags: [],
  type: SPAN_TYPE.general,
  parent_span_id: "",
  trace_id: "trace-1",
  project_id: "project-1",
  ...overrides,
});

// The SDK marks hidden-by-default spans with the is_internal flag.
const hiddenMeta = { [METADATA_OPIK_KEY]: { is_internal: true } };

describe("hiddenSpans", () => {
  describe("isSpanHiddenByDefault / hasHiddenSpans", () => {
    it("should flag a span when the SDK marks it internal", () => {
      expect(
        isSpanHiddenByDefault(makeSpan({ id: "a", metadata: hiddenMeta })),
      ).toBe(true);
      expect(isSpanHiddenByDefault(makeSpan({ id: "b" }))).toBe(false);
      expect(
        isSpanHiddenByDefault(
          makeSpan({ id: "c", metadata: { [METADATA_OPIK_KEY]: {} } }),
        ),
      ).toBe(false);
    });

    it("should report true when a span list contains a hidden-by-default span", () => {
      expect(hasHiddenSpans([makeSpan({ id: "a" })])).toBe(false);
      expect(
        hasHiddenSpans([
          makeSpan({ id: "a" }),
          makeSpan({ id: "b", metadata: hiddenMeta }),
        ]),
      ).toBe(true);
    });
  });

  describe("excludeHiddenSpans", () => {
    it("should drop hidden spans and re-parent children to the nearest visible ancestor when collapsing", () => {
      const spans = [
        makeSpan({ id: "root", parent_span_id: "" }),
        makeSpan({
          id: "hidden",
          parent_span_id: "root",
          metadata: hiddenMeta,
        }),
        makeSpan({ id: "child", parent_span_id: "hidden" }),
        makeSpan({ id: "visibleChild", parent_span_id: "root" }),
      ];

      const result = excludeHiddenSpans(spans);
      const byId = new Map(result.map((s) => [s.id, s]));

      expect(result.map((s) => s.id).sort()).toEqual([
        "child",
        "root",
        "visibleChild",
      ]);
      expect(byId.get("child")?.parent_span_id).toBe("root");
      expect(byId.get("visibleChild")?.parent_span_id).toBe("root");
    });

    it("should re-parent to the trace root when all ancestors are hidden", () => {
      const spans = [
        makeSpan({ id: "hiddenA", parent_span_id: "", metadata: hiddenMeta }),
        makeSpan({
          id: "hiddenB",
          parent_span_id: "hiddenA",
          metadata: hiddenMeta,
        }),
        makeSpan({ id: "leaf", parent_span_id: "hiddenB" }),
      ];

      const result = excludeHiddenSpans(spans);

      expect(result.map((s) => s.id)).toEqual(["leaf"]);
      expect(result[0].parent_span_id).toBe("");
    });

    it("should not mutate the original spans when excluding hidden ones", () => {
      const spans = [
        makeSpan({ id: "hidden", parent_span_id: "", metadata: hiddenMeta }),
        makeSpan({ id: "child", parent_span_id: "hidden" }),
      ];

      excludeHiddenSpans(spans);

      expect(spans[1].parent_span_id).toBe("hidden");
    });
  });
});
