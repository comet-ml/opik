import { describe, expect, it, vi } from "vitest";
import { OpikCallbackHandler, trackLangGraph } from "../src/index";

vi.mock("opik-langchain", () => {
  const MockOpikCallbackHandler = vi
    .fn()
    .mockImplementation(() => ({
      name: "OpikCallbackHandler",
      flushAsync: vi.fn().mockResolvedValue(undefined),
    }));

  return {
    OpikCallbackHandler: MockOpikCallbackHandler,
  };
});

describe("integration exports", () => {
  it("exports trackLangGraph", () => {
    expect(trackLangGraph).toBeDefined();
    expect(typeof trackLangGraph).toBe("function");
  });

  it("re-exports OpikCallbackHandler", () => {
    expect(OpikCallbackHandler).toBeDefined();
    expect(typeof OpikCallbackHandler).toBe("function");
  });
});
