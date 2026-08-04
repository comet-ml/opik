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

  it("always resolves the workspace to v2", () => {
    setLocation("http://localhost/opik/ws-1");
    render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );
    expect(mocks.setWorkspaceVersion).toHaveBeenCalledWith("v2");
  });

  it("never reloads: v2 is forced regardless of what the API reports", () => {
    setLocation("http://localhost/opik/ws-1/experiments/exp-42/compare");
    mocks.state.gateVersion = "v2";
    mocks.state.apiVersion = "v1";

    render(
      <WorkspaceVersionResolver>
        <div />
      </WorkspaceVersionResolver>,
    );

    expect(window.location.replace).not.toHaveBeenCalled();
  });

  it("caches the detected version when the API resolves", () => {
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
});
