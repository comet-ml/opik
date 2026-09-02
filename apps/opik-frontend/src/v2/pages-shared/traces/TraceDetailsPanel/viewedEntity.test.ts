import { describe, it, expect } from "vitest";
import { Span, Trace } from "@/types/traces";
import { resolveViewedEntity } from "./viewedEntity";

const TRACE = { id: "t1" } as Trace;
const SPAN_A = { id: "a" } as Span;

// The quick filter routes on the entity this returns. Whenever the panel shows
// the trace, the filter has to go to the Traces view — otherwise a trace
// attribute lands in `spans_filters` and that table comes back empty.
describe("resolveViewedEntity", () => {
  it.each([
    ["while the spans are still loading", "b", undefined, TRACE, "trace"],
    ["when the span belongs to another trace", "z", [SPAN_A], TRACE, "trace"],
    ["when the span is loaded", "a", [SPAN_A], SPAN_A, "span"],
  ])(
    "shows the right entity %s, and names the same one",
    (_label, spanId, spans, data, entity) => {
      expect(
        resolveViewedEntity(
          spanId as string,
          spans as Span[] | undefined,
          TRACE,
        ),
      ).toEqual({ data, entity });
    },
  );
});
