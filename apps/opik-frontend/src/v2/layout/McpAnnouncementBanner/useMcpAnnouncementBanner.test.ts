import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import { useMcpAnnouncementBanner } from "./useMcpAnnouncementBanner";

// ── mutable state the mock factories read ──────────────────────────────────
// Hoisted, because vi.mock factories are lifted above ordinary declarations.
const { storage } = vi.hoisted(() => ({
  storage: {} as Record<string, unknown>,
}));
let mockKillSwitch: boolean | undefined;
let mockDemoBannerVisible = false;
let mockOnDemoProjectPage = false;
let mockDemoSettled = true;
// ───────────────────────────────────────────────────────────────────────────

vi.mock("use-local-storage-state", async () => ({
  default: (
    await import("@/testing/localStorageStateMock")
  ).createLocalStorageStateMock(storage),
}));

vi.mock("posthog-js/react", () => ({
  useFeatureFlagEnabled: () => mockKillSwitch,
}));

// The demo rule is its own seam with its own tests; here it is a boundary.
vi.mock("@/v2/layout/DemoProjectBanner/useDemoProjectBannerVisibility", () => ({
  useDemoProjectBannerVisibility: () => ({
    isBannerVisible: mockDemoBannerVisible,
    isOnDemoProjectPage: mockOnDemoProjectPage,
    isSettled: mockDemoSettled,
  }),
}));

const INSIDE_WINDOW = "2026-10-01T09:00:00Z";
const LAST_DAY_OF_CAMPAIGN = "2026-11-15T23:30:00Z";
const AFTER_WINDOW = "2026-11-16T00:30:00Z";

const SETTLED_AND_CLEAR = {
  retentionBannerVisible: false,
  retentionBannerSettled: true,
};

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  mockKillSwitch = undefined;
  mockDemoBannerVisible = false;
  mockOnDemoProjectPage = false;
  mockDemoSettled = true;
  vi.useFakeTimers();
  vi.setSystemTime(new Date(INSIDE_WINDOW));
});

afterEach(() => {
  vi.useRealTimers();
});

const banner = (params = SETTLED_AND_CLEAR) =>
  renderHook(() => useMcpAnnouncementBanner(params)).result;

describe("useMcpAnnouncementBanner", () => {
  describe("campaign window", () => {
    it("is visible inside the campaign window", () => {
      expect(banner().current.visible).toBe(true);
    });

    it("is visible on the last day of the campaign", () => {
      vi.setSystemTime(new Date(LAST_DAY_OF_CAMPAIGN));
      expect(banner().current.visible).toBe(true);
    });

    it("is hidden once the campaign window has passed", () => {
      vi.setSystemTime(new Date(AFTER_WINDOW));
      expect(banner().current.visible).toBe(false);
    });
  });

  describe("dismissal", () => {
    it("hides immediately when dismissed", () => {
      const result = banner();

      act(() => result.current.dismiss());

      expect(result.current.visible).toBe(false);
    });

    it("stays hidden for a freshly mounted banner", () => {
      const first = banner();

      act(() => first.current.dismiss());

      expect(banner().current.visible).toBe(false);
    });
  });

  describe("standing aside", () => {
    it("is hidden while the quota banner holds the slot", () => {
      expect(
        banner({ retentionBannerVisible: true, retentionBannerSettled: true })
          .current.visible,
      ).toBe(false);
    });

    it("is hidden while the demo-project banner is on screen", () => {
      mockDemoBannerVisible = true;

      expect(banner().current.visible).toBe(false);
    });

    // The demo banner takes itself off screen once onboarding is done, so the
    // page itself has to be checked too — otherwise the announcement turns up
    // on seeded demo data, and into the funnel with it.
    it("is hidden on a demo project's own page even with no demo banner", () => {
      mockOnDemoProjectPage = true;

      expect(banner().current.visible).toBe(false);
    });

    it("is visible on a workspace page while the demo project is merely active", () => {
      expect(banner().current.visible).toBe(true);
    });
  });

  describe("kill switch", () => {
    it("is hidden when the switch resolves to false", () => {
      mockKillSwitch = false;

      expect(banner().current.visible).toBe(false);
    });

    it("is visible when the switch resolves to true", () => {
      mockKillSwitch = true;

      expect(banner().current.visible).toBe(true);
    });

    // Unresolved is the normal first-render state on cloud and the permanent
    // state in OSS, where PostHog never initialises. Treating it as "hide"
    // would delay the first paint and blank OSS entirely.
    it("is visible while the switch is unresolved", () => {
      mockKillSwitch = undefined;

      expect(banner().current.visible).toBe(true);
    });
  });

  describe("counting an impression", () => {
    it("is countable when it is visible and every verdict has resolved", () => {
      expect(banner().current.countable).toBe(true);
    });

    // The banner is painted optimistically while the verdicts are in flight.
    // Counting it then would put a user we are about to hide it from into the
    // funnel, and burn the session's one impression doing it.
    it("is not countable while the demo verdict is in flight", () => {
      mockDemoSettled = false;

      const { current } = banner();

      expect(current.visible).toBe(true);
      expect(current.countable).toBe(false);
    });

    // The quota banner's own visibility arrives from a query too, so "no quota
    // banner yet" is not the same answer as "no quota banner".
    it("is not countable while the quota verdict is in flight", () => {
      const { current } = banner({
        retentionBannerVisible: false,
        retentionBannerSettled: false,
      });

      expect(current.visible).toBe(true);
      expect(current.countable).toBe(false);
    });

    it("is not countable when it is not visible at all", () => {
      mockDemoBannerVisible = true;

      expect(banner().current.countable).toBe(false);
    });
  });
});
