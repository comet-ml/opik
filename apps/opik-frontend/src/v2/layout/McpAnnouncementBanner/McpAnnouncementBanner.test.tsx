import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpAnnouncementBanner from "./McpAnnouncementBanner";
import {
  MCP_BANNER_CAMPAIGN_ID,
  MCP_BANNER_COPY,
  MCP_BANNER_COPY_SHORT,
  MCP_BANNER_HEIGHT,
  MCP_BANNER_HEIGHT_CLASS,
  MCP_BANNER_SHOWN_SESSION_KEY,
} from "./constants";

// ── mutable state the mock factories read ──────────────────────────────────
// Hoisted, because vi.mock factories are lifted above ordinary declarations.
const { storage } = vi.hoisted(() => ({
  storage: {} as Record<string, unknown>,
}));
let mockIsPhone = false;
let mockDemoSettled = true;
// ───────────────────────────────────────────────────────────────────────────

vi.mock("use-local-storage-state", async () => ({
  default: (
    await import("@/testing/localStorageStateMock")
  ).createLocalStorageStateMock(storage),
}));

vi.mock("@/store/AppStore", () => ({
  default: { getState: () => ({ activeWorkspaceName: "my-workspace" }) },
}));

// The height wiring belongs to the layout, not to this seam.
vi.mock("@/hooks/useObserveResizeNode", () => ({
  useObserveResizeNode: () => ({ ref: vi.fn(), node: undefined }),
}));

// Boundaries of the visibility rule, each with its own tests elsewhere. Here
// they are held at "nothing else is competing for the slot".
vi.mock("posthog-js/react", () => ({
  useFeatureFlagEnabled: () => undefined,
}));

vi.mock("@/v2/layout/DemoProjectBanner/useDemoProjectBannerVisibility", () => ({
  useDemoProjectBannerVisibility: () => ({
    isBannerVisible: false,
    isOnDemoProjectPage: false,
    isSettled: mockDemoSettled,
  }),
}));

vi.mock("@/hooks/useIsPhone", () => ({
  useIsPhone: () => ({
    isPhone: mockIsPhone,
    isPhonePortrait: mockIsPhone,
    isPhoneLandscape: false,
  }),
}));

// The app mounts one provider at the root; the dismiss control's tooltip needs
// it here too.
const renderBanner = (props?: { retentionBannerSettled?: boolean }) =>
  render(
    <TooltipProvider>
      <McpAnnouncementBanner
        onChangeHeight={vi.fn()}
        retentionBannerVisible={false}
        retentionBannerSettled={props?.retentionBannerSettled ?? true}
      />
    </TooltipProvider>,
  );

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  window.sessionStorage.clear();
  mockIsPhone = false;
  mockDemoSettled = true;
  vi.mocked(trackEvent).mockClear();
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date("2026-10-01T09:00:00Z"));
});

afterEach(() => {
  vi.useRealTimers();
});

vi.mock("@/lib/analytics/tracking", async () => {
  const actual = await vi.importActual<
    typeof import("@/lib/analytics/tracking")
  >("@/lib/analytics/tracking");
  return { ...actual, trackEvent: vi.fn() };
});

describe("McpAnnouncementBanner", () => {
  it("announces MCP with a link to the docs", () => {
    renderBanner();

    expect(screen.getByText(MCP_BANNER_COPY)).toBeInTheDocument();

    const cta = screen.getByRole("link", { name: /learn more/i });
    expect(cta).toHaveAttribute(
      "href",
      expect.stringContaining("/docs/opik/mcp-server"),
    );
    expect(cta).toHaveAttribute("target", "_blank");
  });

  it("reports the impression once per session, across remounts", () => {
    renderBanner().unmount();
    renderBanner();

    const impressions = vi
      .mocked(trackEvent)
      .mock.calls.filter(([event]) => event === OpikEvent.MCP_BANNER_SHOWN);

    expect(impressions).toHaveLength(1);
    expect(impressions[0][1]).toEqual({
      workspace_name: "my-workspace",
      campaign_id: MCP_BANNER_CAMPAIGN_ID,
      copy_variant: "full",
    });
    expect(
      window.sessionStorage.getItem(MCP_BANNER_SHOWN_SESSION_KEY),
    ).not.toBeNull();
  });

  it("reports a click on the call to action", () => {
    renderBanner();

    fireEvent.click(screen.getByRole("link", { name: /learn more/i }));

    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.MCP_BANNER_CTA_CLICKED, {
      workspace_name: "my-workspace",
      campaign_id: MCP_BANNER_CAMPAIGN_ID,
      copy_variant: "full",
    });
  });

  it("reports a dismissal and takes the bar off screen", () => {
    renderBanner();

    fireEvent.click(screen.getByRole("button", { name: /dismiss/i }));

    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.MCP_BANNER_DISMISSED, {
      workspace_name: "my-workspace",
      campaign_id: MCP_BANNER_CAMPAIGN_ID,
      copy_variant: "full",
    });
    expect(screen.queryByText(MCP_BANNER_COPY)).not.toBeInTheDocument();
  });

  it("stays dismissed on the next visit, and reports no impression", () => {
    renderBanner();
    fireEvent.click(screen.getByRole("button", { name: /dismiss/i }));

    window.sessionStorage.clear();
    vi.mocked(trackEvent).mockClear();
    renderBanner();

    expect(screen.queryByText(MCP_BANNER_COPY)).not.toBeInTheDocument();
    expect(trackEvent).not.toHaveBeenCalledWith(
      OpikEvent.MCP_BANNER_SHOWN,
      expect.anything(),
    );
  });

  it("is exposed to assistive technology as a named region", () => {
    renderBanner();

    expect(screen.getByRole("region", { name: /opik mcp/i })).toBeVisible();
  });

  describe("on a phone", () => {
    it("shows a message that fits, and says so in the events", () => {
      mockIsPhone = true;

      renderBanner();

      expect(screen.getByText(MCP_BANNER_COPY_SHORT)).toBeInTheDocument();
      expect(screen.queryByText(MCP_BANNER_COPY)).not.toBeInTheDocument();
      expect(trackEvent).toHaveBeenCalledWith(OpikEvent.MCP_BANNER_SHOWN, {
        workspace_name: "my-workspace",
        campaign_id: MCP_BANNER_CAMPAIGN_ID,
        copy_variant: "short",
      });
    });

    it("keeps both controls reachable next to the shortened message", () => {
      mockIsPhone = true;

      renderBanner();

      expect(screen.getByRole("link", { name: /learn more/i })).toBeVisible();
      expect(screen.getByRole("button", { name: /dismiss/i })).toBeVisible();
    });
  });

  describe("while a suppression verdict is in flight", () => {
    it("shows the bar but does not spend the session's impression on the demo verdict", () => {
      mockDemoSettled = false;

      renderBanner();

      expect(screen.getByText(MCP_BANNER_COPY)).toBeInTheDocument();
      expect(trackEvent).not.toHaveBeenCalledWith(
        OpikEvent.MCP_BANNER_SHOWN,
        expect.anything(),
      );
      expect(
        window.sessionStorage.getItem(MCP_BANNER_SHOWN_SESSION_KEY),
      ).toBeNull();
    });

    it("does not spend it on the quota verdict either", () => {
      renderBanner({ retentionBannerSettled: false });

      expect(screen.getByText(MCP_BANNER_COPY)).toBeInTheDocument();
      expect(trackEvent).not.toHaveBeenCalledWith(
        OpikEvent.MCP_BANNER_SHOWN,
        expect.anything(),
      );
    });
  });
});

describe("the height the layout is told about", () => {
  it("matches the class that actually sets it", () => {
    // The layout offsets its content by MCP_BANNER_HEIGHT before the bar is
    // measured. If the class and the constant disagree, the page shifts by the
    // difference on every load, which is the one thing this banner must not do.
    const classHeightRem =
      Number(MCP_BANNER_HEIGHT_CLASS.replace("h-", "")) * 0.25;

    expect(classHeightRem * 16).toBe(MCP_BANNER_HEIGHT);
  });
});
