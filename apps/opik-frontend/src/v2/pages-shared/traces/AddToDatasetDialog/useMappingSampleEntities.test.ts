import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

const mockUseTracesByIds = vi.fn();
const mockUseSpansByIds = vi.fn();

vi.mock("@/api/traces/useTracesByIds", () => ({
  default: (params: { traceIds: string[] }) => mockUseTracesByIds(params),
}));

vi.mock("@/api/traces/useSpansByIds", () => ({
  default: (params: { spanIds: string[] }) => mockUseSpansByIds(params),
}));

import useMappingSampleEntities, {
  MAPPING_SAMPLE_SIZE,
} from "./useMappingSampleEntities";
import { Span, Trace } from "@/types/traces";

const trace = (id: string, extra: object = {}) =>
  ({ id, input: { input_text: `text-${id}` }, ...extra }) as unknown as Trace;

const renderSample = (
  rows: Array<Trace | Span>,
  { enabled = true, hasOnlySpans = false } = {},
) =>
  renderHook(() =>
    useMappingSampleEntities({
      validTraces: hasOnlySpans ? [] : rows,
      validSpans: hasOnlySpans ? rows : [],
      hasOnlySpans,
      enabled,
    }),
  );

describe("useMappingSampleEntities", () => {
  beforeEach(() => {
    mockUseTracesByIds.mockReset();
    mockUseSpansByIds.mockReset();
    mockUseSpansByIds.mockReturnValue([]);
  });

  it("fetches nothing while disabled", () => {
    mockUseTracesByIds.mockReturnValue([]);

    const { result } = renderSample([trace("a")], { enabled: false });

    expect(mockUseTracesByIds).toHaveBeenCalledWith({
      traceIds: [],
      stripAttachments: true,
    });
    expect(result.current.treeData).toEqual({});
  });

  it("caps the fetch at the sample size", () => {
    mockUseTracesByIds.mockReturnValue([]);
    const rows = Array.from({ length: MAPPING_SAMPLE_SIZE + 3 }, (_, index) =>
      trace(`t${index}`),
    );

    renderSample(rows);

    expect(mockUseTracesByIds.mock.calls[0][0].traceIds).toHaveLength(
      MAPPING_SAMPLE_SIZE,
    );
  });

  it("unions keys across the sample and keeps the first value for a shared leaf", () => {
    mockUseTracesByIds.mockReturnValue([
      { data: { id: "a", input: { input_text: "first" } }, isPending: false },
      {
        data: { id: "b", input: { input_text: "second", tone: "neutral" } },
        isPending: false,
      },
    ]);

    const { result } = renderSample([trace("a"), trace("b")]);

    expect(result.current.treeData).toEqual({
      id: "a",
      input: { input_text: "first", tone: "neutral" },
    });
    expect(result.current.entities).toHaveLength(2);
  });

  it("reads spans when the selection is spans only", () => {
    mockUseTracesByIds.mockReturnValue([]);
    mockUseSpansByIds.mockReturnValue([
      { data: { id: "s1", input: { q: "hi" } }, isPending: false },
    ]);

    const { result } = renderSample([trace("s1")], { hasOnlySpans: true });

    expect(mockUseSpansByIds).toHaveBeenCalledWith({
      spanIds: ["s1"],
      stripAttachments: true,
    });
    expect(result.current.treeData).toEqual({ id: "s1", input: { q: "hi" } });
  });
});
