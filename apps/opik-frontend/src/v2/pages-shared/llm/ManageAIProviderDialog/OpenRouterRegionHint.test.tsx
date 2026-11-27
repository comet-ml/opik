import React from "react";
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import OpenRouterRegionHint from "./OpenRouterRegionHint";
import { OpenRouterRegion } from "@/types/providers";

const { useOpenRouterRegion } = vi.hoisted(() => ({
  useOpenRouterRegion: vi.fn(),
}));

vi.mock("@/api/provider-keys/useOpenRouterRegion", () => ({
  default: useOpenRouterRegion,
}));

const renderHint = (region?: OpenRouterRegion) => {
  useOpenRouterRegion.mockReturnValue({ data: region });
  render(<OpenRouterRegionHint />);
};

describe("OpenRouterRegionHint", () => {
  it.each([
    ["global", "Region: Global"],
    ["eu", "Region: European Union"],
    ["us", "Region: United States"],
  ] as const)("shows the %s region", (region, text) => {
    renderHint(region);

    expect(screen.getByTestId("openrouter-region-hint")).toHaveTextContent(
      text,
    );
  });

  it.each(["unknown", undefined] as const)("shows nothing for %s", (region) => {
    renderHint(region);

    expect(
      screen.queryByTestId("openrouter-region-hint"),
    ).not.toBeInTheDocument();
  });
});
