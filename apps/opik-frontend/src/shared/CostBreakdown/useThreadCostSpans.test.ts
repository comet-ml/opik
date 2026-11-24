import { describe, expect, it } from "vitest";
import { UseQueryResult } from "@tanstack/react-query";
import { UseSpansListResponse } from "@/api/traces/useSpansList";
import { Span } from "@/types/traces";
import { combineSpanQueries } from "./useThreadCostSpans";

const spanWithId = (id: string) => ({ id }) as Span;

const page = (spans: Span[], total: number): UseSpansListResponse => ({
  content: spans,
  total,
  sortable_by: [],
});

const query = (
  state: Partial<UseQueryResult<UseSpansListResponse>>,
): UseQueryResult<UseSpansListResponse> =>
  ({
    isPending: false,
    isError: false,
    ...state,
  }) as UseQueryResult<UseSpansListResponse>;

describe("combineSpanQueries", () => {
  it("joins every trace's spans and says when one trace has more than it loaded", () => {
    const combined = combineSpanQueries([
      query({ data: page([spanWithId("a")], 1) }),
      query({ data: page([spanWithId("b"), spanWithId("c")], 5) }),
    ]);

    expect(combined.spans.map((span) => span.id)).toEqual(["a", "b", "c"]);
    expect(combined.isTruncated).toBe(true);
    expect(combined.isPending).toBe(false);
    expect(combined.isError).toBe(false);
  });

  it("stays pending while any trace loads, and fails when any trace fails", () => {
    const combined = combineSpanQueries([
      query({ data: page([spanWithId("a")], 1) }),
      query({ isPending: true }),
      query({ isError: true }),
    ]);

    expect(combined.spans.map((span) => span.id)).toEqual(["a"]);
    expect(combined.isPending).toBe(true);
    expect(combined.isError).toBe(true);
    expect(combined.isTruncated).toBe(false);
  });

  it("has nothing to show for a thread without costed traces", () => {
    expect(combineSpanQueries([])).toEqual({
      spans: [],
      isPending: false,
      isError: false,
      isTruncated: false,
    });
  });
});
