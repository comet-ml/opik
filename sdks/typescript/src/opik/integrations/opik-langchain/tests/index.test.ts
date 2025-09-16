import { describe, it, expect } from "vitest";
import { OpikCallbackHandler } from "../src/index";

describe("Integration Export", () => {
  it("should export OpikCallbackHandler", () => {
    expect(OpikCallbackHandler).toBeDefined();
    expect(typeof OpikCallbackHandler).toBe("function");
  });

  it("should create an instance of OpikCallbackHandler", () => {
    // Mock the Opik constructor to avoid actual initialization
    const handler = new OpikCallbackHandler();

    expect(handler).toBeInstanceOf(OpikCallbackHandler);
    expect(handler.name).toBe("OpikCallbackHandler");
  });

  it("should have required callback methods", () => {
    const handler = new OpikCallbackHandler();

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
