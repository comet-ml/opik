import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import OptimizationCompareRedirect from "./OptimizationCompareRedirect";

const mockNavigate = vi.fn();

let mockSearchStr = "";

vi.mock("@tanstack/react-router", () => ({
  Navigate: (props: Record<string, unknown>) => {
    mockNavigate(props);
    return null;
  },
  useRouterState: ({ select }: { select: (s: unknown) => unknown }) =>
    select({ location: { searchStr: mockSearchStr } }),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector: (state: Record<string, unknown>) => unknown) =>
    selector({ activeWorkspaceName: "default", activeProjectId: "proj-123" }),
  ),
  useActiveProjectId: () => "proj-123",
}));

describe("OptimizationCompareRedirect", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockSearchStr = "";
  });

  it("redirects legacy compare URL to new optimization detail URL", () => {
    mockSearchStr = `optimizations=${encodeURIComponent(
      JSON.stringify(["opt-123-abc"]),
    )}`;

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
      params: {
        workspaceName: "default",
        projectId: "proj-123",
        optimizationId: "opt-123-abc",
      },
      replace: true,
    });
  });

  it("uses the first optimization ID when multiple are provided", () => {
    mockSearchStr = `optimizations=${encodeURIComponent(
      JSON.stringify(["opt-first", "opt-second"]),
    )}`;

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        params: expect.objectContaining({ optimizationId: "opt-first" }),
      }),
    );
  });

  it("redirects to optimizations list when no optimization ID is provided", () => {
    mockSearchStr = "";

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/optimizations",
      params: { workspaceName: "default", projectId: "proj-123" },
      replace: true,
    });
  });

  it("redirects to optimizations list when optimization IDs array is empty", () => {
    mockSearchStr = `optimizations=${encodeURIComponent(JSON.stringify([]))}`;

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/optimizations",
      params: { workspaceName: "default", projectId: "proj-123" },
      replace: true,
    });
  });
});
