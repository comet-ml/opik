import { beforeEach, describe, expect, it, vi } from "vitest";
import { trackLangGraph } from "../src/trackLangGraph";
import { OpikCallbackHandler } from "opik-langchain";

vi.mock("opik-langchain", () => {
  const MockOpikCallbackHandler = vi
    .fn()
    .mockImplementation((options: Record<string, unknown> = {}) => ({
      name: "OpikCallbackHandler",
      options,
      flushAsync: vi.fn().mockResolvedValue(undefined),
    }));

  return {
    OpikCallbackHandler: MockOpikCallbackHandler,
  };
});

type MockGraph = {
  config?: Record<string, unknown>;
  getGraph?: ReturnType<typeof vi.fn>;
  flush?: () => Promise<void>;
};

const createMockGraph = (): MockGraph => ({
  config: {},
  getGraph: vi.fn().mockReturnValue({
    drawMermaid: vi.fn().mockReturnValue("graph TD; A-->B"),
  }),
});

describe("trackLangGraph", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("injects Opik callback and graph definition metadata", async () => {
    const graph = createMockGraph();

    const trackedGraph = trackLangGraph(graph, {
      projectName: "langgraph-project",
      tags: ["langgraph"],
    });

    const callbacks = trackedGraph.config?.callbacks as unknown[];
    expect(Array.isArray(callbacks)).toBe(true);
    expect(callbacks).toHaveLength(1);

    expect(trackedGraph.config?.metadata).toEqual({
      _opik_graph_definition: {
        format: "mermaid",
        data: "graph TD; A-->B",
      },
    });

    expect(typeof trackedGraph.flush).toBe("function");
    await trackedGraph.flush();
    expect(trackedGraph.opikCallbackHandler.flushAsync).toHaveBeenCalledTimes(1);
  });

  it("does not inject duplicate handlers when called multiple times", () => {
    const graph = createMockGraph();

    const trackedGraph = trackLangGraph(graph);
    trackLangGraph(trackedGraph);

    const callbacks = trackedGraph.config?.callbacks as unknown[];
    expect(callbacks).toHaveLength(1);
    expect(OpikCallbackHandler).toHaveBeenCalledTimes(1);
  });

  it("reuses an existing Opik callback handler when already present", async () => {
    const existingHandler = {
      name: "OpikCallbackHandler",
      flushAsync: vi.fn().mockResolvedValue(undefined),
    };

    const graph = createMockGraph();
    graph.config = {
      callbacks: [existingHandler],
    };

    const trackedGraph = trackLangGraph(graph);

    expect(OpikCallbackHandler).not.toHaveBeenCalled();
    expect(trackedGraph.opikCallbackHandler).toBe(existingHandler);

    await trackedGraph.flush();
    expect(existingHandler.flushAsync).toHaveBeenCalledTimes(1);
  });

  it("adds handler using callback manager when callbacks is a manager object", () => {
    const handlers: unknown[] = [];
    const callbackManager = {
      handlers,
      addHandler: vi.fn((handler: unknown) => {
        handlers.push(handler);
      }),
    };

    const graph = createMockGraph();
    graph.config = {
      callbacks: callbackManager,
    };

    trackLangGraph(graph);

    expect(callbackManager.addHandler).toHaveBeenCalledTimes(1);
    expect(callbackManager.handlers).toHaveLength(1);
  });

  it("supports providing an explicit callback handler instance", () => {
    const graph = createMockGraph();
    const handler = new OpikCallbackHandler();

    const trackedGraph = trackLangGraph(graph, handler);

    const callbacks = trackedGraph.config?.callbacks as unknown[];
    expect(callbacks[0]).toBe(handler);
  });

  it("can disable graph definition metadata injection", () => {
    const graph = createMockGraph();

    const trackedGraph = trackLangGraph(graph, {
      includeGraphDefinition: false,
    });

    expect(trackedGraph.getGraph).not.toHaveBeenCalled();
    expect(trackedGraph.config?.metadata).toBeUndefined();
  });
});
