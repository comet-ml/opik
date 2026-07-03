import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";

import TrialsRedirect from "./TrialsRedirect";

const mockNavigate = vi.fn();

let mockSearch: Record<string, unknown> = {};
let mockParams: Record<string, string | undefined> = {};

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => mockParams,
  useRouterState: ({ select }: { select: (s: unknown) => unknown }) =>
    select({ location: { search: mockSearch } }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "default",
}));

describe("TrialsRedirect", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockSearch = {};
    mockParams = { projectId: "proj-1", optimizationId: "opt-1" };
  });

  it("redirects onto the run overview preserving the trial params", () => {
    mockSearch = { trials: ["exp-1", "exp-2"], trialNumber: 2 };

    render(<TrialsRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
      params: {
        workspaceName: "default",
        projectId: "proj-1",
        optimizationId: "opt-1",
      },
      search: { trials: ["exp-1", "exp-2"], trialNumber: 2 },
      replace: true,
    });
  });

  it("omits search when there are no query params", () => {
    render(<TrialsRedirect />);

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({ search: undefined, replace: true }),
    );
  });

  it("does not redirect until route params are available", () => {
    mockParams = {};
    render(<TrialsRedirect />);
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
