import { renderHook } from "@testing-library/react";
import { useQuery } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

import useSelectedSpanData, {
  type SpanWithOptionalPayload,
} from "@/api/traces/useSelectedSpanData";
import { SPANS_KEY } from "@/api/api";
import { Span, Trace } from "@/types/traces";

vi.mock("@tanstack/react-query", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-query")>();
  return {
    ...actual,
    useQuery: vi.fn(),
  };
});

const useQueryMock = vi.mocked(useQuery);

const makeSpan = (overrides: Partial<Span> = {}) =>
  ({
    id: "span-1",
    project_id: "project-1",
    trace_id: "trace-1",
    input: { prompt: "hello" },
    output: { response: "world" },
    ...overrides,
  }) as Span;

const makeTrace = (overrides: Partial<Trace> = {}) =>
  ({
    id: "trace-1",
    input: { prompt: "trace" },
    output: { response: "trace" },
    ...overrides,
  }) as Trace;

const makeLightSpan = (overrides: Partial<SpanWithOptionalPayload> = {}) =>
  ({
    ...makeSpan(),
    input: null,
    output: null,
    ...overrides,
  }) as SpanWithOptionalPayload;

const mockQuery = (overrides: Partial<ReturnType<typeof useQuery>> = {}) => {
  useQueryMock.mockReturnValue({
    data: undefined,
    isError: false,
    isFetching: false,
    isPlaceholderData: false,
    ...overrides,
  } as unknown as ReturnType<typeof useQuery>);
};

describe("useSelectedSpanData", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockQuery();
  });

  it("reuses selected list span when it already has payload data", () => {
    const span = makeSpan();

    const { result } = renderHook(() =>
      useSelectedSpanData({
        projectId: span.project_id,
        spanId: span.id,
        traceId: span.trace_id,
        spans: [span],
        trace: makeTrace(),
      }),
    );

    expect(useQueryMock).toHaveBeenCalledWith(
      expect.objectContaining({
        enabled: false,
        placeholderData: span,
        queryKey: [
          SPANS_KEY,
          {
            projectId: span.project_id,
            spanId: span.id,
            stripAttachments: true,
          },
        ],
      }),
    );
    expect(result.current.dataToView).toBe(span);
    expect(result.current.isSelectedSpanPending).toBe(false);
  });

  it("fetches a selected lightweight span before showing span details", () => {
    const trace = makeTrace();
    const span = makeLightSpan();
    mockQuery({ isFetching: true, isPlaceholderData: true });

    const { result } = renderHook(() =>
      useSelectedSpanData({
        projectId: span.project_id,
        spanId: span.id,
        traceId: span.trace_id,
        spans: [span],
        trace,
      }),
    );

    expect(useQueryMock).toHaveBeenCalledWith(
      expect.objectContaining({
        enabled: true,
        placeholderData: span,
      }),
    );
    expect(result.current.dataToView).toMatchObject({
      id: span.id,
      trace_id: span.trace_id,
      input: {},
      output: {},
    });
    expect(result.current.isSelectedSpanPending).toBe(true);
  });

  it("uses the fetched full span only when it belongs to the selected trace", () => {
    const trace = makeTrace();
    const fetchedSpan = makeSpan();
    mockQuery({ data: fetchedSpan });

    const { result, unmount } = renderHook(() =>
      useSelectedSpanData({
        projectId: fetchedSpan.project_id,
        spanId: fetchedSpan.id,
        traceId: trace.id,
        spans: [makeLightSpan()],
        trace,
      }),
    );

    expect(result.current.dataToView).toBe(fetchedSpan);
    expect(result.current.selectedSpanData).toBe(fetchedSpan);

    unmount();
    mockQuery({ data: makeSpan({ trace_id: "trace-2" }) });
    const { result: wrongTraceResult } = renderHook(() =>
      useSelectedSpanData({
        projectId: fetchedSpan.project_id,
        spanId: fetchedSpan.id,
        traceId: trace.id,
        spans: [makeLightSpan()],
        trace,
      }),
    );

    expect(wrongTraceResult.current.dataToView).toMatchObject({
      id: fetchedSpan.id,
      trace_id: trace.id,
      input: {},
      output: {},
    });
    expect(wrongTraceResult.current.selectedSpanData).toBeUndefined();
  });
});
