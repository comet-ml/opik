import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import OptimizationCompareRedirect from "./OptimizationCompareRedirect";

const mockNavigate = vi.fn();

vi.mock("@tanstack/react-router", () => ({
  Navigate: (props: Record<string, unknown>) => {
    mockNavigate(props);
    return null;
  },
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector: (state: Record<string, unknown>) => unknown) =>
    selector({ activeWorkspaceName: "default" }),
  ),
}));

let mockOptimizationsIds: string[] | undefined;

vi.mock("use-query-params", () => ({
  JsonParam: {},
  useQueryParam: () => [mockOptimizationsIds],
}));

describe("OptimizationCompareRedirect", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockOptimizationsIds = undefined;
  });

  it("redirects legacy compare URL to new optimization detail URL", () => {
    mockOptimizationsIds = ["opt-123-abc"];

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/optimizations/$optimizationId",
      params: { workspaceName: "default", optimizationId: "opt-123-abc" },
      replace: true,
    });
  });

  it("uses the first optimization ID when multiple are provided", () => {
    mockOptimizationsIds = ["opt-first", "opt-second"];

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        params: expect.objectContaining({ optimizationId: "opt-first" }),
      }),
    );
  });

  it("redirects to optimizations list when no optimization ID is provided", () => {
    mockOptimizationsIds = undefined;

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/optimizations",
      params: { workspaceName: "default" },
      replace: true,
    });
  });

  it("redirects to optimizations list when optimization IDs array is empty", () => {
    mockOptimizationsIds = [];

    render(<OptimizationCompareRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/optimizations",
      params: { workspaceName: "default" },
      replace: true,
    });
  });
});
