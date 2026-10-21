import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import { useMcpAnnouncementBanner } from "./useMcpAnnouncementBanner";

const { storage } = vi.hoisted(() => ({
  storage: {} as Record<string, unknown>,
}));
let mockKillSwitch: boolean | undefined;
let mockDemoBannerVisible = false;
let mockOnDemoProjectPage = false;

vi.mock("use-local-storage-state", async () => ({
  default: (
    await import("@/testing/localStorageStateMock")
  ).createLocalStorageStateMock(storage),
}));

vi.mock("posthog-js/react", () => ({
  useFeatureFlagEnabled: () => mockKillSwitch,
}));

vi.mock("@/v2/layout/DemoProjectBanner/useDemoProjectBannerVisibility", () => ({
  useDemoProjectBannerVisibility: () => ({
    isBannerVisible: mockDemoBannerVisible,
    isOnDemoProjectPage: mockOnDemoProjectPage,
    isSettled: true,
  }),
}));

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  mockKillSwitch = undefined;
  mockDemoBannerVisible = false;
  mockOnDemoProjectPage = false;
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-01T09:00:00Z"));
});

afterEach(() => {
  vi.useRealTimers();
});

const banner = (retentionBannerVisible = false) =>
  renderHook(() => useMcpAnnouncementBanner({ retentionBannerVisible })).result;

describe("useMcpAnnouncementBanner", () => {
  it("shows during the campaign", () => {
    expect(banner().current.visible).toBe(true);
  });

  it("hides after the campaign end date", () => {
    vi.setSystemTime(new Date("2026-11-16T00:30:00Z"));
    expect(banner().current.visible).toBe(false);
  });

  it("hides once dismissed, and stays hidden on the next mount", () => {
    const first = banner();
    act(() => first.current.dismiss());

    expect(first.current.visible).toBe(false);
    expect(banner().current.visible).toBe(false);
  });

  it("hides while the demo banner is up", () => {
    mockDemoBannerVisible = true;
    expect(banner().current.visible).toBe(false);
  });

  it("hides on a demo project's page", () => {
    mockOnDemoProjectPage = true;
    expect(banner().current.visible).toBe(false);
  });

  it("hides while the quota banner is up", () => {
    expect(banner(true).current.visible).toBe(false);
  });

  it("hides when the kill switch is false, shows while it is unresolved", () => {
    mockKillSwitch = false;
    expect(banner().current.visible).toBe(false);

    mockKillSwitch = undefined;
    expect(banner().current.visible).toBe(true);
  });
});
