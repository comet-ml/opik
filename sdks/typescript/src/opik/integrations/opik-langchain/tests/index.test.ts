import { describe, it, expect, vi, beforeEach } from "vitest";
import { OpikCallbackHandler } from "../src/index";
import type { Opik } from "opik";

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

const createMockOpikClient = (): Opik =>
  ({
    trace: vi.fn(),
    flush: vi.fn().mockResolvedValue(undefined),
    config: {
      projectName: undefined,
    },
  }) as unknown as Opik;

describe("Integration Export", () => {
  let mockOpikClient: Opik;

  beforeEach(() => {
    vi.clearAllMocks();
    mockOpikClient = createMockOpikClient();
  });

  it("should export OpikCallbackHandler", () => {
    expect(OpikCallbackHandler).toBeDefined();
    expect(typeof OpikCallbackHandler).toBe("function");
  });

  it("should create an instance of OpikCallbackHandler", () => {
    // Use mocked client to avoid API key requirement
    const handler = new OpikCallbackHandler({ client: mockOpikClient });

    expect(handler).toBeInstanceOf(OpikCallbackHandler);
    expect(handler.name).toBe("OpikCallbackHandler");
  });

  it("should have required callback methods", () => {
    const handler = new OpikCallbackHandler({ client: mockOpikClient });

    // Check that essential callback methods exist
    expect(typeof handler.handleChainStart).toBe("function");
    expect(typeof handler.handleChainEnd).toBe("function");
    expect(typeof handler.handleChainError).toBe("function");
    expect(typeof handler.handleLLMStart).toBe("function");
    expect(typeof handler.handleLLMEnd).toBe("function");
    expect(typeof handler.handleLLMError).toBe("function");
    expect(typeof handler.handleToolStart).toBe("function");
    expect(typeof handler.handleToolEnd).toBe("function");
    expect(typeof handler.handleToolError).toBe("function");
    expect(typeof handler.flushAsync).toBe("function");
  });
});
