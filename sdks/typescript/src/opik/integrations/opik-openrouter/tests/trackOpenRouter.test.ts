import { describe, expect, it, vi } from "vitest";

const trackOpenAIMock = vi.hoisted(() => vi.fn());

vi.mock("opik-openai", () => ({
  trackOpenAI: trackOpenAIMock,
}));

import { trackOpenRouter } from "../src/trackOpenRouter";

describe("trackOpenRouter", () => {
  it("forces provider to openrouter", () => {
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
});
