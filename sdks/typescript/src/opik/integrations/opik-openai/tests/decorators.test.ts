import { withTracing, resolveProvider } from "../src/decorators";
import type { Opik } from "opik";

const createMockSpan = () => ({
  span: vi.fn(),
  update: vi.fn(),
  end: vi.fn(),
});

const createMockOpikClient = (
  rootSpan: ReturnType<typeof createMockSpan>
): Opik => ({ trace: vi.fn().mockReturnValue(rootSpan) } as unknown as Opik);

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

describe("OpenRouter metadata mapping", () => {
  it("captures request routing metadata in initial span metadata", async () => {
    const rootSpan = createMockSpan();
    const mockClient = createMockOpikClient(rootSpan);

    const traced = withTracing(
      vi.fn().mockResolvedValue({
        model: "openai/gpt-4o-mini",
        choices: [{ message: { role: "assistant", content: "Hello" } }],
        usage: {
          prompt_tokens: 12,
          completion_tokens: 8,
          total_tokens: 20,
        },
      }),
      {
        generationName: "openrouter-routing-request",
        provider: "openrouter",
        client: mockClient,
      }
    );

    await traced({
      model: "openai/gpt-4o-mini",
      messages: [{ role: "user", content: "Hello" }],
      provider: {
        order: ["deepinfra/turbo"],
        allow_fallbacks: false,
        require_parameters: true,
      },
    });

    expect(mockClient.trace).toHaveBeenCalledTimes(1);
    expect(rootSpan.span).toHaveBeenCalledTimes(1);

    const tracePayload = rootSpan.span.mock.calls[0]?.[0];
    expect(tracePayload.metadata).toMatchObject({
      openrouter_routing: {
        order: ["deepinfra/turbo"],
        allow_fallbacks: false,
        require_parameters: true,
      },
    });
  });

  it("captures provider fields from response metadata", async () => {
    const rootSpan = createMockSpan();
    const mockClient = createMockOpikClient(rootSpan);

    const traced = withTracing(
      vi.fn().mockResolvedValue({
        model: "openai/gpt-4o-mini",
        choices: [{ message: { role: "assistant", content: "Hello" } }],
        provider: "openai/gpt-4o-mini",
        provider_name: "openrouter-openai",
        provider_id: "openrouter/openai/gpt-4o-mini",
        model_provider: "openrouter",
        routing: {
          order: ["anthropic/claude-3", "openai/gpt-4o-mini"],
          allow_fallbacks: true,
        },
        usage: {
          prompt_tokens: 11,
          completion_tokens: 9,
          total_tokens: 20,
        },
      }),
      {
        generationName: "openrouter-routing-response",
        provider: "openrouter",
        client: mockClient,
      }
    );

    await traced({
      model: "openai/gpt-4o-mini",
      messages: [{ role: "user", content: "Hello" }],
    });

    expect(mockClient.trace).toHaveBeenCalledTimes(1);
    expect(rootSpan.span).toHaveBeenCalledTimes(1);

    const spanPayload = rootSpan.span.mock.calls[0]?.[0];
    expect(spanPayload.metadata).toMatchObject({
      openrouter_provider: "openai/gpt-4o-mini",
      openrouter_provider_name: "openrouter-openai",
      openrouter_provider_id: "openrouter/openai/gpt-4o-mini",
      openrouter_model_provider: "openrouter",
      openrouter_routing: {
        order: ["anthropic/claude-3", "openai/gpt-4o-mini"],
        allow_fallbacks: true,
      },
    });
  });
});
