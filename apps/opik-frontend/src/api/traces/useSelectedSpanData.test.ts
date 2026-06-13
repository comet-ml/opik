import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import useSpanById from "@/api/traces/useSpanById";
import useSelectedSpanData from "@/api/traces/useSelectedSpanData";
import { Span, Trace } from "@/types/traces";

vi.mock("@/api/traces/useSpanById", () => ({
  default: vi.fn(),
}));

const useSpanByIdMock = vi.mocked(useSpanById);

const makeSpan = (overrides: Partial<Span> = {}) =>
  ({
    id: "span-1",
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

const mockSpanById = (
  overrides: Partial<ReturnType<typeof useSpanById>> = {},
) => {
  useSpanByIdMock.mockReturnValue({
    data: undefined,
    isError: false,
    isFetching: false,
    isPlaceholderData: false,
    ...overrides,
  } as unknown as ReturnType<typeof useSpanById>);
};

describe("useSelectedSpanData", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("reuses selected list span when it already has input and output", () => {
    const span = makeSpan();
    mockSpanById({ data: span });

    const { result } = renderHook(() =>
      useSelectedSpanData({
        spanId: span.id,
        traceId: span.trace_id,
        spans: [span],
        trace: makeTrace(),
      }),
    );

    expect(useSpanByIdMock).toHaveBeenCalledWith(
      { spanId: span.id, stripAttachments: true },
      expect.objectContaining({
        enabled: false,
        placeholderData: span,
      }),
    );
    expect(result.current.dataToView).toBe(span);
    expect(result.current.isSelectedSpanPending).toBe(false);
  });

  it("prefers refreshed full list span over stale by-id data", () => {
    const listSpan = makeSpan({
      input: { prompt: "fresh" },
      output: { response: "fresh" },
    });
    const staleHydratedSpan = makeSpan({
      input: { prompt: "stale" },
      output: { response: "stale" },
    });
    mockSpanById({ data: staleHydratedSpan });

    const { result } = renderHook(() =>
      useSelectedSpanData({
        spanId: listSpan.id,
        traceId: listSpan.trace_id,
        spans: [listSpan],
        trace: makeTrace(),
      }),
    );

    expect(useSpanByIdMock).toHaveBeenCalledWith(
      { spanId: listSpan.id, stripAttachments: true },
      expect.objectContaining({ enabled: false }),
    );
    expect(result.current.dataToView).toBe(listSpan);
    expect(result.current.selectedSpanData).toBe(listSpan);
  });

  it("hydrates selected list span when excluded payload fields are null", () => {
    const span = makeSpan({
      input: null,
      output: null,
    });
    mockSpanById({
      data: span,
      isFetching: true,
      isPlaceholderData: true,
    });

    const { result } = renderHook(() =>
      useSelectedSpanData({
        spanId: span.id,
        traceId: span.trace_id,
        spans: [span],
        trace: makeTrace(),
      }),
    );

    expect(useSpanByIdMock).toHaveBeenCalledWith(
      { spanId: span.id, stripAttachments: true },
      expect.objectContaining({
        enabled: true,
        placeholderData: span,
      }),
    );
    expect(result.current.isSelectedSpanPending).toBe(true);
  });

  it("falls back to the trace when fetched span belongs to another trace", () => {
    const trace = makeTrace();
    const fetchedSpan = makeSpan({ trace_id: "trace-2" });
    mockSpanById({ data: fetchedSpan });

    const { result } = renderHook(() =>
      useSelectedSpanData({
        spanId: fetchedSpan.id,
        traceId: trace.id,
        spans: [],
        trace,
      }),
    );

    expect(useSpanByIdMock).toHaveBeenCalledWith(
      { spanId: fetchedSpan.id, stripAttachments: true },
      expect.objectContaining({
        enabled: true,
        placeholderData: undefined,
      }),
    );
    expect(result.current.selectedSpanData).toBeUndefined();
    expect(result.current.dataToView).toBe(trace);
  });
});
