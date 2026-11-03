import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import useTracesList from "@/api/traces/useTracesList";
import useAnnotatorTracePreviewSamples from "./useAnnotatorTracePreviewSamples";

vi.mock("@/api/traces/useTracesList", () => ({ default: vi.fn() }));

const traces = (count: number, prefix: string) =>
  Array.from({ length: count }, (_, index) => ({ id: `${prefix}-${index}` }));

let queueContent: { id: string }[] | undefined;
let projectContent: { id: string }[] | undefined;
let projectQueryEnabled: boolean[] = [];

const mockedUseTracesList = vi.mocked(useTracesList);

const renderSamples = (queueId?: string) =>
  renderHook(() =>
    useAnnotatorTracePreviewSamples({
      projectId: "project",
      queueId,
      isThreadScope: false,
      enabled: true,
    }),
  );

describe("useAnnotatorTracePreviewSamples", () => {
  beforeEach(() => {
    queueContent = undefined;
    projectContent = traces(3, "project");
    projectQueryEnabled = [];
    mockedUseTracesList.mockImplementation(((
      params: { filters?: unknown },
      options: { enabled?: boolean },
    ) => {
      const isQueueQuery = Boolean(params.filters);
      if (!isQueueQuery) projectQueryEnabled.push(Boolean(options.enabled));
      const content = isQueueQuery ? queueContent : projectContent;
      const data = options.enabled && content;
      return {
        data: data ? { content: data, total: data.length } : undefined,
        isPending: !data,
      };
    }) as unknown as typeof useTracesList);
  });

  it("clamps the index when fewer samples arrive", () => {
    queueContent = traces(3, "queue");
    const { result, rerender } = renderSamples("queue");

    act(() => result.current.setSampleIndex(2));
    expect(result.current.sample?.id).toBe("queue-2");

    queueContent = traces(2, "queue");
    rerender();

    expect(result.current.sampleIndex).toBe(1);
    expect(result.current.sample?.id).toBe("queue-1");

    act(() => result.current.setSampleIndex(result.current.sampleIndex - 1));
    expect(result.current.sample?.id).toBe("queue-0");
  });

  it("skips the project query while queue samples load or exist", () => {
    const { result, rerender } = renderSamples("queue");
    expect(result.current.isPending).toBe(true);

    queueContent = traces(2, "queue");
    rerender();

    expect(projectQueryEnabled.every((enabled) => !enabled)).toBe(true);
    expect(result.current.samples.map((trace) => trace.id)).toEqual([
      "queue-0",
      "queue-1",
    ]);
  });

  it("falls back to project samples when the queue is empty", () => {
    queueContent = [];
    const { result } = renderSamples("queue");

    expect(projectQueryEnabled.at(-1)).toBe(true);
    expect(result.current.samples).toHaveLength(3);
  });

  it("uses project samples for a new queue", () => {
    const { result } = renderSamples();

    expect(projectQueryEnabled.at(-1)).toBe(true);
    expect(result.current.sample?.id).toBe("project-0");
  });
});
