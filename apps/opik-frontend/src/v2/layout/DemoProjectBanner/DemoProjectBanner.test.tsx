import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";

import DemoProjectBanner from "./DemoProjectBanner";
import { DEMO_BANNER_HEIGHT, DEMO_BANNER_HEIGHT_CLASS } from "./constants";

// ── mutable state the mock factories read ──────────────────────────────────
let mockBannerVisible = false;
// ───────────────────────────────────────────────────────────────────────────

vi.mock("./useDemoProjectBannerVisibility", () => ({
  useDemoProjectBannerVisibility: () => ({
    isBannerVisible: mockBannerVisible,
    isOnDemoProjectPage: mockBannerVisible,
    isSettled: true,
    isDemoProjectActive: true,
    isOnboardingActive: true,
    isManualFlow: true,
    onboardingState: { step: "connect-agent", agentName: "my-agent" },
    setOnboardingState: vi.fn(),
  }),
}));

vi.mock("./useAutoCompleteAgentOnboarding", () => ({
  default: () => undefined,
}));

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "my-workspace",
}));

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

// Measurement is unavailable in this environment, which is the point: the
// layout must be told the height without waiting for a measurement.
vi.mock("@/hooks/useObserveResizeNode", () => ({
  useObserveResizeNode: () => ({ ref: vi.fn(), node: undefined }),
}));

beforeEach(() => {
  mockBannerVisible = false;
});

describe("DemoProjectBanner", () => {
  it("publishes no height while it is not on screen", () => {
    const onChangeHeight = vi.fn();

    render(<DemoProjectBanner onChangeHeight={onChangeHeight} />);

    expect(onChangeHeight).toHaveBeenLastCalledWith(0);
  });

  // The regression this guards: visibility now resolves from a query, so the
  // bar appears a beat after mount. Publishing whatever a resize observer had
  // measured by then left the layout at 0 and the bar overlapping the content.
  it("publishes its height as soon as it appears, without a measurement", () => {
    const onChangeHeight = vi.fn();
    const { rerender } = render(
      <DemoProjectBanner onChangeHeight={onChangeHeight} />,
    );

    mockBannerVisible = true;
    rerender(<DemoProjectBanner onChangeHeight={onChangeHeight} />);

    expect(onChangeHeight).toHaveBeenLastCalledWith(DEMO_BANNER_HEIGHT);
  });

  it("goes back to publishing nothing when it leaves the screen", () => {
    const onChangeHeight = vi.fn();
    mockBannerVisible = true;
    const { rerender } = render(
      <DemoProjectBanner onChangeHeight={onChangeHeight} />,
    );

    mockBannerVisible = false;
    rerender(<DemoProjectBanner onChangeHeight={onChangeHeight} />);

    expect(onChangeHeight).toHaveBeenLastCalledWith(0);
  });
});

describe("the height the layout is told about", () => {
  it("matches the class that actually sets it", () => {
    const classHeightRem =
      Number(DEMO_BANNER_HEIGHT_CLASS.replace("h-", "")) * 0.25;

    expect(classHeightRem * 16).toBe(DEMO_BANNER_HEIGHT);
  });
});
