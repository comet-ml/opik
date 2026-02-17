import { describe, expect, it, vi, beforeEach } from "vitest";

const withTracingMock = vi.hoisted(() => vi.fn());

vi.mock("../src/decorators", () => ({
  withTracing: withTracingMock,
}));

import { trackOpenAI } from "../src/trackOpenAI";

describe("trackOpenAI", () => {
  beforeEach(() => {
    withTracingMock.mockReset();
  });

  it("does not clobber explicitly configured provider", () => {
    const sdk = {
      baseURL: "https://openrouter.ai/api/v1",
      chat: {
        completions: {
          create: vi.fn(),
        },
      },
    };

    const tracked = trackOpenAI(sdk, { provider: "explicit-provider" });
    tracked.chat.completions.create;

    expect(withTracingMock).toHaveBeenCalledTimes(1);
    expect(withTracingMock.mock.calls[0]?.[1].provider).toBe(
      "explicit-provider"
    );
  });

  it("falls back to detected provider when no provider is configured", () => {
    const sdk = {
      baseURL: "https://openrouter.ai/api/v1",
      completions: {
        create: vi.fn(),
      },
    };

    const tracked = trackOpenAI(sdk);
    tracked.completions.create;

    expect(withTracingMock).toHaveBeenCalledTimes(1);
    expect(withTracingMock.mock.calls[0]?.[1].provider).toBe("openrouter");
  });
});
