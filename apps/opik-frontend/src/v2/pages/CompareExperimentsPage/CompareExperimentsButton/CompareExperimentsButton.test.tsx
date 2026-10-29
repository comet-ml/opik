import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { Experiment } from "@/types/datasets";
import CompareExperimentsButton from "./CompareExperimentsButton";

const mockNavigate = vi.fn();
vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
}));

vi.mock(
  "@/v2/pages/CompareExperimentsPage/useDatasetIdFromCompareExperimentsURL",
  () => ({
    useDatasetIdFromCompareExperimentsURL: () => "dataset-1",
  }),
);

const mockSetExperimentsIds = vi.fn();
vi.mock("use-query-params", async (importOriginal) => ({
  ...(await importOriginal<typeof import("use-query-params")>()),
  useQueryParam: () => [["exp-1"], mockSetExperimentsIds],
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
    }),
  ),
  useActiveProjectId: () => "test-project-id",
}));

const buildExperiment = (id: string) =>
  ({
    id,
    name: `Experiment ${id}`,
    dataset_id: "dataset-1",
    created_at: "2026-09-01T00:00:00Z",
  }) as Experiment;

const mockUseExperimentsList = vi.fn();
vi.mock("@/api/datasets/useExperimentsList", () => ({
  default: (...args: unknown[]) => mockUseExperimentsList(...args),
}));

describe("CompareExperimentsButton", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseExperimentsList.mockReturnValue({
      data: {
        content: [buildExperiment("exp-1"), buildExperiment("exp-2")],
        total: 2,
      },
      isPending: false,
    });
  });

  it("writes the picked experiments to the URL without navigating away", () => {
    render(
      <TooltipProvider>
        <CompareExperimentsButton />
      </TooltipProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Compare" }));
    fireEvent.click(screen.getByText("Experiment exp-2"));
    fireEvent.click(
      screen.getByRole("button", { name: "Compare 2 experiments" }),
    );

    expect(mockUseExperimentsList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        filters: [expect.objectContaining({ value: "dataset-1" })],
      }),
      expect.anything(),
    );
    expect(mockSetExperimentsIds).toHaveBeenCalledWith(["exp-1", "exp-2"]);
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
