import { describe, expect, it, vi } from "vitest";

const trackOpenAIMock = vi.hoisted(() => vi.fn());

vi.mock("opik-openai", () => ({
  trackOpenAI: trackOpenAIMock,
}));

import { trackOpenRouter } from "../src/trackOpenRouter";

describe("trackOpenRouter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("defaults provider to openrouter when not configured", () => {
    trackOpenAIMock.mockReturnValue("tracked-sdk");

    const sdk = {
      chat: {
        send: vi.fn(),
      },
    } as const;

    const opikConfig = {
      traceMetadata: {
        tags: ["gateway"],
      },
      generationName: "openrouter-call",
    };

    const trackedSdk = trackOpenRouter(sdk, opikConfig);

    expect(trackOpenAIMock).toHaveBeenCalledTimes(1);
    expect(trackOpenAIMock).toHaveBeenCalledWith(sdk, {
      ...opikConfig,
      provider: "openrouter",
    });
    expect(trackedSdk).toBe("tracked-sdk");
  });

  it("keeps explicit provider when configured", () => {
    trackOpenAIMock.mockReturnValue("tracked-sdk");

    const sdk = {
      chat: {
        send: vi.fn(),
      },
    } as const;

    const opikConfig = {
      provider: "custom-provider",
      traceMetadata: {
        tags: ["gateway"],
      },
      generationName: "openrouter-call",
    };

    const trackedSdk = trackOpenRouter(sdk, opikConfig);

    expect(trackOpenAIMock).toHaveBeenCalledTimes(1);
    expect(trackOpenAIMock).toHaveBeenCalledWith(sdk, {
      ...opikConfig,
      provider: "custom-provider",
    });
    expect(trackedSdk).toBe("tracked-sdk");
  });
});
