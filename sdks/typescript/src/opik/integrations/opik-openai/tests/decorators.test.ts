import { resolveProvider } from "../src/decorators";

describe("resolveProvider", () => {
  it("uses explicit trace metadata provider when provided", () => {
    expect(
      resolveProvider({
        model: "openrouter/openai/gpt-4o",
        traceMetadata: { provider: "openrouter" },
        providerHint: "openai",
      })
    ).toBe("openrouter");
  });

  it("infers provider from openrouter model path", () => {
    expect(
      resolveProvider({
        model: "openrouter/anthropic/claude-3.5-sonnet",
        traceMetadata: {},
        providerHint: "openai",
      })
    ).toBe("anthropic");
  });

  it("infers provider from model namespace", () => {
    expect(
      resolveProvider({
        model: "google/gemini-2.5-flash",
        traceMetadata: {},
        providerHint: "openai",
      })
    ).toBe("google");
  });

  it("uses provider hint when model does not encode provider", () => {
    expect(
      resolveProvider({
        model: "gpt-4o-mini",
        traceMetadata: {},
        providerHint: "openrouter",
      })
    ).toBe("openrouter");
  });

  it("falls back to openai when nothing else is available", () => {
    expect(
      resolveProvider({
        model: "gpt-4o-mini",
        traceMetadata: {},
        providerHint: undefined,
      })
    ).toBe("openai");
  });
});
