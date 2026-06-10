import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render } from "@testing-library/react";
import WorkspaceVersionResolver from "./WorkspaceVersionResolver";

const mocks = vi.hoisted(() => {
  return {
    setWorkspaceVersion: vi.fn(),
    setDetectedWorkspaceVersion: vi.fn(),
    setCachedWorkspaceVersion: vi.fn(),
    state: {
      activeWorkspaceName: "ws-1" as string | null,
      gateVersion: "v2" as "v1" | "v2" | null,
      apiVersion: undefined as "v1" | "v2" | undefined,
      override: null as "v1" | "v2" | null,
      optIn: false,
    },
  };
});

vi.mock("@/store/AppStore", () => ({
  default: {
    getState: () => ({
      setWorkspaceVersion: mocks.setWorkspaceVersion,
      setDetectedWorkspaceVersion: mocks.setDetectedWorkspaceVersion,
    }),
  },
  useActiveWorkspaceName: () => mocks.state.activeWorkspaceName,
  useWorkspaceVersion: () => mocks.state.gateVersion,
}));

vi.mock("@/api/workspaces/useWorkspaceVersion", () => ({
  default: () => ({ data: mocks.state.apiVersion }),
}));

vi.mock("@/lib/workspaceVersion", () => ({
  getVersionOverride: () => mocks.state.override,
  getNewExperienceOptIn: () => mocks.state.optIn,
  setCachedWorkspaceVersion: mocks.setCachedWorkspaceVersion,
}));

const setLocation = (href: string) => {
  Object.defineProperty(window, "location", {
    configurable: true,
    value: {
      href,
      replace: vi.fn(),
    },
  });
};

describe("WorkspaceVersionResolver", () => {
  beforeEach(() => {
    mocks.setWorkspaceVersion.mockClear();
    mocks.setDetectedWorkspaceVersion.mockClear();
    mocks.setCachedWorkspaceVersion.mockClear();
    sessionStorage.clear();
    mocks.state.activeWorkspaceName = "ws-1";
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = undefined;
    mocks.state.override = null;
    mocks.state.optIn = false;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders children immediately (no blocking Loader)", () => {
    setLocation("http://localhost/opik/ws-1");
    const { getByTestId } = render(
      <WorkspaceVersionResolver>
        <div data-testid="child" />
      </WorkspaceVersionResolver>,
    );
    expect(getByTestId("child")).toBeTruthy();
  });

  it("no reload when api version matches gate version", () => {
    setLocation("http://localhost/opik/ws-1");
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = "v2";

    render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(window.location.replace).not.toHaveBeenCalled();
  });

  it("on mismatch, reloads to the URL captured at first render — not the current URL", () => {
    const originalUrl = "http://localhost/opik/ws-1/experiments/exp-42/compare";
    setLocation(originalUrl);
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = "v1";

    const { rerender } = render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    // Simulate a child redirect component mutating the URL BEFORE the
    // resolver's effect runs on the next commit.
    const replaceFn = window.location.replace as ReturnType<typeof vi.fn>;
    setLocation("http://localhost/opik/ws-1/projects/proj-1/experiments");
    // Carry the spy across the location reset so we can still assert on it.
    (window.location as { replace: unknown }).replace = replaceFn;

    rerender(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(window.location.replace).toHaveBeenCalledWith(originalUrl);
  });

  it("caches detected version on every resolve", () => {
    setLocation("http://localhost/opik/ws-1");
    mocks.state.apiVersion = "v2";

    render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(mocks.setCachedWorkspaceVersion).toHaveBeenCalledWith("ws-1", "v2");
    expect(mocks.setDetectedWorkspaceVersion).toHaveBeenCalledWith("v2");
  });

  it("stops reloading after MAX_RELOADS to avoid infinite loops", () => {
    setLocation("http://localhost/opik/ws-1/experiments/exp-42");
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = "v1";
    sessionStorage.setItem("opik-version-reload:ws-1", "2");

    render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(window.location.replace).not.toHaveBeenCalled();
  });

  it("clears reload counter when no mismatch", () => {
    setLocation("http://localhost/opik/ws-1");
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = "v2";
    sessionStorage.setItem("opik-version-reload:ws-1", "1");

    render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(sessionStorage.getItem("opik-version-reload:ws-1")).toBeNull();
  });

  it("after a successful verification, a later mismatch reloads to the then-current URL (stale capture cleared)", () => {
    // 1. First render on ws-1 with a correct version → URL captured & cleared.
    const firstUrl = "http://localhost/opik/ws-1";
    setLocation(firstUrl);
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = "v2";

    const { rerender } = render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    // 2. Simulate a new optimistic mount later: user navigates to a deep link
    // within ws-1, then a fresh mismatch fires (e.g. the admin flipped the
    // workspace's version server-side). The resolver should reload to the
    // CURRENT URL — not the stale `firstUrl` captured on the first mount.
    const freshUrl = "http://localhost/opik/ws-1/experiments/exp-99/compare";
    const replaceFn = window.location.replace as ReturnType<typeof vi.fn>;
    setLocation(freshUrl);
    (window.location as { replace: unknown }).replace = replaceFn;
    mocks.state.apiVersion = "v1";

    rerender(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(window.location.replace).toHaveBeenCalledWith(freshUrl);
    expect(window.location.replace).not.toHaveBeenCalledWith(firstUrl);
  });

  it("keeps per-workspace captures independent — ws-B mismatch does not reload to ws-A's URL", () => {
    // Render once on ws-A (verifying) — ws-A URL gets captured.
    const wsAUrl = "http://localhost/opik/ws-A/experiments/exp-1";
    setLocation(wsAUrl);
    mocks.state.activeWorkspaceName = "ws-A";
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = undefined;

    const { rerender } = render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    // Switch to ws-B before ws-A verification ever resolved; fire mismatch
    // on ws-B. Reload target must be ws-B's URL, not the stale ws-A capture.
    const wsBUrl = "http://localhost/opik/ws-B/projects";
    const replaceFn = window.location.replace as ReturnType<typeof vi.fn>;
    setLocation(wsBUrl);
    (window.location as { replace: unknown }).replace = replaceFn;
    mocks.state.activeWorkspaceName = "ws-B";
    mocks.state.apiVersion = "v1";

    rerender(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(window.location.replace).toHaveBeenCalledWith(wsBUrl);
    expect(window.location.replace).not.toHaveBeenCalledWith(wsAUrl);
  });
});
