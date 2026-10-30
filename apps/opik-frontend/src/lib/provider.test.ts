import { describe, expect, it } from "vitest";
import { getOpenRouterBaseUrl, openRouterRegionFromBaseUrl } from "./provider";

describe("openRouterRegionFromBaseUrl", () => {
  it.each([
    [undefined, "global"],
    [null, "global"],
    ["", "global"],
    ["  ", "global"],
    ["https://us.openrouter.ai/api/v1", "us"],
    ["https://eu.openrouter.ai/api/v1/", "eu"],
    ["https://openrouter.ai/api/v1", "custom"],
    ["https://openrouter-proxy.example.com/api/v1", "custom"],
  ])("maps %s to %s", (baseUrl, region) => {
    expect(openRouterRegionFromBaseUrl(baseUrl)).toBe(region);
  });
});

describe("getOpenRouterBaseUrl", () => {
  it.each([
    ["global", "https://ignored.example.com/api/v1", ""],
    ["us", undefined, "https://us.openrouter.ai/api/v1"],
    ["eu", undefined, "https://eu.openrouter.ai/api/v1"],
    [
      "custom",
      " https://openrouter-proxy.example.com/api/v1 ",
      "https://openrouter-proxy.example.com/api/v1",
    ],
  ] as const)("maps %s to its base URL", (region, customBaseUrl, expected) => {
    expect(getOpenRouterBaseUrl(region, customBaseUrl)).toBe(expected);
  });

  it("round-trips every region through the stored base URL", () => {
    (["global", "us", "eu"] as const).forEach((region) => {
      expect(
        openRouterRegionFromBaseUrl(getOpenRouterBaseUrl(region, undefined)),
      ).toBe(region);
    });
  });
});
