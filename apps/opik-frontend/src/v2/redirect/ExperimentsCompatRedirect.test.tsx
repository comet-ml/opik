import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import ExperimentsCompatRedirect from "./ExperimentsCompatRedirect";

let mockLocationSearch: Record<string, unknown> = {};
let mockExperimentQuery: {
  data?: { project_id?: string };
  isLoading: boolean;
} = { isLoading: false };
const mockUseExperimentById = vi.fn();

vi.mock("@tanstack/react-router", () => ({
  useLocation: () => ({ search: mockLocationSearch }),
}));

vi.mock("@/api/datasets/useExperimentById", () => ({
  default: (...args: unknown[]) => {
    mockUseExperimentById(...args);
    return mockExperimentQuery;
  },
}));

vi.mock("@/shared/Loader/Loader", () => ({
  default: () => <div data-testid="loader" />,
}));

vi.mock("@/v2/redirect/V1CompatRedirect", () => ({
  default: ({ toPath, projectId }: { toPath: string; projectId?: string }) => (
    <div
      data-testid="redirect"
      data-to-path={toPath}
      data-project-id={projectId ?? ""}
    />
  ),
}));

describe("ExperimentsCompatRedirect", () => {
  beforeEach(() => {
    mockUseExperimentById.mockClear();
    mockLocationSearch = {};
    mockExperimentQuery = { isLoading: false };
  });

  it("redirects to the project of the first experiment in the link", () => {
    mockLocationSearch = { experiments: ["exp-1", "exp-2"] };
    mockExperimentQuery = {
      data: { project_id: "proj-of-exp" },
      isLoading: false,
    };

    render(<ExperimentsCompatRedirect toPath="/experiments" />);

    expect(mockUseExperimentById).toHaveBeenCalledWith(
      { experimentId: "exp-1" },
      expect.objectContaining({ enabled: true }),
    );
    expect(screen.getByTestId("redirect").dataset.projectId).toBe(
      "proj-of-exp",
    );
  });

  it("waits for the experiment before redirecting", () => {
    mockLocationSearch = { experiments: ["exp-1"] };
    mockExperimentQuery = { isLoading: true };

    render(<ExperimentsCompatRedirect toPath="/experiments" />);

    expect(screen.getByTestId("loader")).toBeTruthy();
    expect(screen.queryByTestId("redirect")).toBeNull();
  });

  it("falls back to the active project when the experiment is not found", () => {
    mockLocationSearch = { experiments: ["missing"] };

    render(<ExperimentsCompatRedirect toPath="/experiments" />);

    expect(screen.getByTestId("redirect").dataset.projectId).toBe("");
  });

  it("skips the lookup when the link has no experiments", () => {
    render(<ExperimentsCompatRedirect toPath="/experiments" />);

    expect(mockUseExperimentById).toHaveBeenCalledWith(
      { experimentId: "" },
      expect.objectContaining({ enabled: false }),
    );
    expect(screen.getByTestId("redirect").dataset.projectId).toBe("");
  });
});
