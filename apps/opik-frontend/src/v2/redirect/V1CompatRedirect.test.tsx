import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import V1CompatRedirect from "./V1CompatRedirect";

const mockNavigate = vi.fn();

let mockActiveProjectId: string | null = "proj-123";
let mockIsProjectLoading = false;
let mockSplat = "";
let mockLocationSearch: Record<string, unknown> = {};

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ _splat: mockSplat }),
  useLocation: () => ({ search: mockLocationSearch }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "default",
  useActiveProjectId: () => mockActiveProjectId,
  useIsProjectLoading: () => mockIsProjectLoading,
}));

vi.mock("@/shared/Loader/Loader", () => ({
  default: () => <div data-testid="loader" />,
}));

describe("V1CompatRedirect", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockActiveProjectId = "proj-123";
    mockIsProjectLoading = false;
    mockSplat = "";
    mockLocationSearch = {};
  });

  it("redirects /$ws/experiments to project-scoped /experiments", () => {
    render(<V1CompatRedirect toPath="/experiments" />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/default/projects/proj-123/experiments",
      search: {},
      replace: true,
    });
  });

  it("redirects /$ws/prompts with splat to project-scoped /prompts/abc", () => {
    mockSplat = "abc123";
    render(<V1CompatRedirect toPath="/prompts" />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/default/projects/proj-123/prompts/abc123",
      search: {},
      replace: true,
    });
  });

  it("redirects to projects list when no active project and not loading", () => {
    mockActiveProjectId = null;
    render(<V1CompatRedirect toPath="/experiments" />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects",
      params: { workspaceName: "default" },
      replace: true,
    });
  });

  it("does not navigate while project is loading", () => {
    mockIsProjectLoading = true;
    mockActiveProjectId = null;
    render(<V1CompatRedirect toPath="/experiments" />);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("redirects /$ws/test-suites with nested path", () => {
    mockSplat = "suite-id/items";
    render(<V1CompatRedirect toPath="/test-suites" />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/default/projects/proj-123/test-suites/suite-id/items",
      search: {},
      replace: true,
    });
  });

  // --- Query param forwarding (OPIK-5239) ---

  it("preserves query params when redirecting", () => {
    mockLocationSearch = { foo: "bar", baz: "123" };
    render(<V1CompatRedirect toPath="/experiments" />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/default/projects/proj-123/experiments",
      search: { foo: "bar", baz: "123" },
      replace: true,
    });
  });

  it("preserves experiment compare query params", () => {
    mockLocationSearch = {
      experiments: ["exp-1", "exp-2"],
    };
    mockSplat = "dataset-123/compare";
    render(<V1CompatRedirect toPath="/experiments" />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/default/projects/proj-123/experiments/dataset-123/compare",
      search: { experiments: ["exp-1", "exp-2"] },
      replace: true,
    });
  });
});
