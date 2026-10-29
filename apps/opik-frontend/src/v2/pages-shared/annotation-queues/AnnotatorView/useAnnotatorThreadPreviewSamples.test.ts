import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import useThreadList from "@/api/traces/useThreadsList";
import useTracesList from "@/api/traces/useTracesList";
import useAnnotatorThreadPreviewSamples from "./useAnnotatorThreadPreviewSamples";

vi.mock("@/api/traces/useThreadsList", () => ({ default: vi.fn() }));
vi.mock("@/api/traces/useTracesList", () => ({ default: vi.fn() }));
vi.mock("@/v2/pages-shared/traces/hiddenSpans", () => ({
  useVisibleTraces: (traces: unknown[]) => traces,
}));

const items = (count: number, prefix: string) =>
  Array.from({ length: count }, (_, index) => ({ id: `${prefix}-${index}` }));

let queueThreads: { id: string }[] | undefined;
let projectThreads: { id: string }[] | undefined;
let threadEnabled: boolean[] = [];
let tracesByThread: Record<string, { id: string }[]> = {};

const renderSamples = (queueId?: string, enabled = true) =>
  renderHook(() =>
    useAnnotatorThreadPreviewSamples({
      projectId: "project",
      queueId,
      enabled,
    }),
  );

describe("useAnnotatorThreadPreviewSamples", () => {
  beforeEach(() => {
    queueThreads = undefined;
    projectThreads = items(3, "project");
    threadEnabled = [];
    tracesByThread = {};
    vi.mocked(useThreadList).mockImplementation(((
      params: { annotationQueueId?: string },
      options: { enabled?: boolean },
    ) => {
      threadEnabled.push(Boolean(options.enabled));
      const content = params.annotationQueueId ? queueThreads : projectThreads;
      const data = options.enabled && content;
      return {
        data: data ? { content: data, total: data.length } : undefined,
        isPending: !data,
      };
    }) as unknown as typeof useThreadList);
    vi.mocked(useTracesList).mockImplementation(((
      params: { filters: { value: string }[] },
      options: { enabled?: boolean },
    ) => {
      const content = options.enabled
        ? tracesByThread[params.filters[0].value]
        : undefined;
      return {
        data: content ? { content, total: content.length } : undefined,
      };
    }) as unknown as typeof useTracesList);
  });

  it("uses the queue threads and the turns of the selected one in order", () => {
    queueThreads = items(2, "queue");
    tracesByThread = {
      "queue-0": [{ id: "b" }, { id: "a" }],
      "queue-1": [{ id: "c" }],
    };
    const { result } = renderSamples("queue");

    expect(result.current.threads.map((thread) => thread.id)).toEqual([
      "queue-0",
      "queue-1",
    ]);
    expect(result.current.traces?.map((trace) => trace.id)).toEqual(["a", "b"]);

    act(() => result.current.setSampleIndex(1));
    expect(result.current.traces?.map((trace) => trace.id)).toEqual(["c"]);
  });

  it("falls back to project threads when the queue is empty", () => {
    queueThreads = [];
    const { result } = renderSamples("queue");

    expect(result.current.thread?.id).toBe("project-0");
  });

  it("fetches nothing for a trace queue", () => {
    renderSamples("queue", false);

    expect(threadEnabled.every((enabled) => !enabled)).toBe(true);
  });
});
