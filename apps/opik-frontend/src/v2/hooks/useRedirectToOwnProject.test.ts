import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { useRedirectToOwnProject } from "./useRedirectToOwnProject";

const mockNavigate = vi.fn();
let mockRouteProjectId: string | undefined = "proj-b";
let mockLocation = {
  pathname: "/ws/projects/proj-b/datasets/ds-1/items",
  search: { size: 10 } as Record<string, unknown>,
};

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ projectId: mockRouteProjectId }),
  useLocation: () => mockLocation,
}));

describe("useRedirectToOwnProject", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockRouteProjectId = "proj-b";
    mockLocation = {
      pathname: "/ws/projects/proj-b/datasets/ds-1/items",
      search: { size: 10 },
    };
  });

  it("moves to the entity's project, keeping the rest of the path and search", () => {
    renderHook(() => useRedirectToOwnProject("proj-a"));

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/ws/projects/proj-a/datasets/ds-1/items",
      search: { size: 10 },
      replace: true,
    });
  });

  it("stays when the entity belongs to the project in the URL", () => {
    renderHook(() => useRedirectToOwnProject("proj-b"));

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("stays while the entity is loading or has no project", () => {
    renderHook(() => useRedirectToOwnProject(undefined));
    renderHook(() => useRedirectToOwnProject(null));

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("stays outside a project-scoped route", () => {
    mockRouteProjectId = undefined;

    renderHook(() => useRedirectToOwnProject("proj-a"));

    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
