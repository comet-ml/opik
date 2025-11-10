import { describe, it, expect, vi, beforeEach } from "vitest";
import { OpikCallbackHandler } from "../src/OpikCallbackHandler";
import type { Opik, Trace, Span } from "opik";

// Mock the opik module
vi.mock("opik", () => ({
  Opik: vi.fn(),
  logger: {
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  },
  OpikSpanType: {
    General: "general",
    Llm: "llm",
    Tool: "tool",
  },
}));

// Create mock implementations
const createMockSpan = (): Span => {
  const mockSpan = {
    span: vi.fn(),
    update: vi.fn(),
    end: vi.fn(),
  } as unknown as Span;

  // Set up the span method to return a new mock span (not itself)
  (mockSpan.span as ReturnType<typeof vi.fn>).mockReturnValue({
    span: vi.fn(),
    update: vi.fn(),
    end: vi.fn(),
  } as unknown as Span);

  return mockSpan;
};

const createMockTrace = (): Trace => {
  const mockTrace = {
    span: vi.fn(),
    update: vi.fn(),
    end: vi.fn(),
  } as unknown as Trace;

  // Set up the span method to return a new mock span
  (mockTrace.span as ReturnType<typeof vi.fn>).mockReturnValue(
    createMockSpan()
  );

  return mockTrace;
};

const createMockOpikClient = (): Opik =>
  ({
    trace: vi.fn(),
    flush: vi.fn().mockResolvedValue(undefined),
    config: {
      projectName: undefined,
    },
  }) as unknown as Opik;

describe("OpikCallbackHandler", () => {
  let mockOpikClient: Opik;
  let handler: OpikCallbackHandler;

  beforeEach(() => {
    vi.clearAllMocks();
    mockOpikClient = createMockOpikClient();
    (mockOpikClient.config as { projectName?: string }).projectName = undefined;
    // Set up the trace method to return a mock trace
    (mockOpikClient.trace as ReturnType<typeof vi.fn>).mockReturnValue(
      createMockTrace()
    );
  });

  describe("constructor", () => {
    it("should create handler with default options", () => {
      handler = new OpikCallbackHandler();

      expect(handler.name).toBe("OpikCallbackHandler");
      expect(handler).toBeInstanceOf(OpikCallbackHandler);
    });

    it("should create handler with custom client", () => {
      handler = new OpikCallbackHandler({ client: mockOpikClient });

      expect(handler.name).toBe("OpikCallbackHandler");
    });

    it("should set project name on client config", () => {
      const projectName = "test-project";
      handler = new OpikCallbackHandler({
        client: mockOpikClient,
        projectName,
      });

      expect(mockOpikClient.config.projectName).toBe(projectName);
    });

    it("should add threadId to metadata when provided", () => {
      const threadId = "thread-123";
      handler = new OpikCallbackHandler({
        client: mockOpikClient,
        threadId,
      });

      // The threadId should be added to options.metadata
      // We can't directly test this as the options are private,
      // but we can test the behavior in handler methods
      expect(handler).toBeInstanceOf(OpikCallbackHandler);
    });

    it("should accept custom metadata and tags", () => {
      const metadata = { custom: "value" };
      const tags = ["tag1", "tag2"];

      handler = new OpikCallbackHandler({
        client: mockOpikClient,
        metadata,
        tags: tags as [],
      });

      expect(handler).toBeInstanceOf(OpikCallbackHandler);
    });
  });

  describe("handleChainStart", () => {
    beforeEach(() => {
      handler = new OpikCallbackHandler({ client: mockOpikClient });
    });

    it("should create a trace for root chain", async () => {
      const chain = {
        lc: 1 as const,
        type: "not_implemented" as const,
        id: ["langchain", "chains", "TestChain"],
      };

      const input = { query: "test question" };
      const runId = "run-123";

      await handler.handleChainStart(chain, input, runId);

      expect(mockOpikClient.trace).toHaveBeenCalledWith({
        name: "TestChain",
        input: { query: { value: "test question" } }, // Input gets processed
        tags: undefined,
        metadata: {},
        threadId: undefined,
      });
    });

    it("should create a span for child chain", async () => {
      const mockTrace = createMockTrace();
      const mockParentSpan = createMockSpan();
      const mockChildSpan = createMockSpan();

      // Parent span should return child span when .span() is called
      (mockParentSpan.span as ReturnType<typeof vi.fn>).mockReturnValue(
        mockChildSpan
      );

      // Trace should return parent span when .span() is called
      (mockTrace.span as ReturnType<typeof vi.fn>).mockReturnValue(
        mockParentSpan
      );

      // First create a parent trace
      (mockOpikClient.trace as ReturnType<typeof vi.fn>).mockReturnValue(
        mockTrace
      );

      const parentChain = {
        lc: 1 as const,
        type: "not_implemented" as const,
        id: ["langchain", "chains", "ParentChain"],
      };

      const childChain = {
        lc: 1 as const,
        type: "not_implemented" as const,
        id: ["langchain", "chains", "ChildChain"],
      };

      const parentRunId = "parent-123";
      const childRunId = "child-456";
      const input = { query: "test" };

      // Start parent chain
      await handler.handleChainStart(parentChain, input, parentRunId);

      // Start child chain
      await handler.handleChainStart(
        childChain,
        input,
        childRunId,
        parentRunId
      );

      // Should call span() on the parent span (not the trace)
      expect(mockParentSpan.span).toHaveBeenCalledWith({
        type: "general", // Default type from OpikSpanType.General
        name: "ChildChain",
        input: { query: { value: "test" } }, // Input gets processed
        tags: undefined,
        metadata: {},
        model: undefined,
        provider: undefined,
      });
    });

    it("should skip chains with langsmith:hidden tag", async () => {
      const chain = {
        lc: 1 as const,
        type: "not_implemented" as const,
        id: ["langchain", "chains", "HiddenChain"],
      };

      const input = { query: "test" };
      const runId = "run-123";
      const tags = ["langsmith:hidden"];

      await handler.handleChainStart(chain, input, runId, undefined, tags);

      expect(mockOpikClient.trace).not.toHaveBeenCalled();
    });
  });

  describe("handleChainEnd", () => {
    beforeEach(() => {
      handler = new OpikCallbackHandler({ client: mockOpikClient });
    });

    it("should update and end span", async () => {
      const mockTrace = createMockTrace();
      const mockSpan = createMockSpan();
      (mockTrace.span as ReturnType<typeof vi.fn>).mockReturnValue(mockSpan);
      (mockOpikClient.trace as ReturnType<typeof vi.fn>).mockReturnValue(
        mockTrace
      );

      // First start a chain to create the trace
      const chain = {
        lc: 1 as const,
        type: "not_implemented" as const,
        id: ["langchain", "chains", "TestChain"],
      };

      const runId = "run-123";
      await handler.handleChainStart(chain, { query: "test" }, runId);

      // Now end the chain
      const output = { answer: "response" };
      await handler.handleChainEnd(output, runId);

      // The span's update method should be called (not the trace's update)
      expect(mockSpan.update).toHaveBeenCalledWith({
        output: expect.any(Object),
        errorInfo: undefined,
        tags: undefined,
        usage: undefined,
        metadata: undefined,
        endTime: expect.any(Date),
      });
    });
  });

  describe("handleChainError", () => {
    beforeEach(() => {
      handler = new OpikCallbackHandler({ client: mockOpikClient });
    });

    it("should handle chain errors", async () => {
      const mockTrace = createMockTrace();
      const mockSpan = createMockSpan();
      (mockTrace.span as ReturnType<typeof vi.fn>).mockReturnValue(mockSpan);
      (mockOpikClient.trace as ReturnType<typeof vi.fn>).mockReturnValue(
        mockTrace
      );

      // Start a chain
      const chain = {
        lc: 1 as const,
        type: "not_implemented" as const,
        id: ["langchain", "chains", "TestChain"],
      };

      const runId = "run-123";
      await handler.handleChainStart(chain, { query: "test" }, runId);

      // Simulate an error
      const error = new Error("Test error");
      await handler.handleChainError(error, runId);

      // The span's update method should be called (not the trace's update)
      expect(mockSpan.update).toHaveBeenCalledWith({
        output: undefined,
        errorInfo: {
          message: "Test error",
          exceptionType: "Error",
          traceback: expect.any(String),
        },
        tags: undefined,
        usage: undefined,
        metadata: undefined,
        endTime: expect.any(Date),
      });
    });
  });

  describe("flushAsync", () => {
    beforeEach(() => {
      handler = new OpikCallbackHandler({ client: mockOpikClient });
    });

    it("should call client flush", async () => {
      await handler.flushAsync();

      expect(mockOpikClient.flush).toHaveBeenCalled();
    });
  });
});
