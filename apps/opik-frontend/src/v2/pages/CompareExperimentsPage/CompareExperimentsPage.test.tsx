import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import CompareExperimentsPage from "./CompareExperimentsPage";
import { EXPERIMENT_TYPE } from "@/types/datasets";

let mockRequestedIds: string[] | undefined;
let mockActiveProjectId: string | null = "proj-a";
let mockProjectExperiments: {
  data?: { content: { id: string }[] };
  isLoading: boolean;
  isError?: boolean;
} = { isLoading: false };
const mockUseExperimentsList = vi.fn();
const mockUseExperimentsByIds = vi.fn();

vi.mock("use-query-params", () => ({
  JsonParam: "json",
  StringParam: "string",
  useQueryParam: (name: string) =>
    name === "experiments" ? [mockRequestedIds, vi.fn()] : [undefined, vi.fn()],
}));

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => mockActiveProjectId,
}));

vi.mock("@/api/datasets/useExperimentsList", () => ({
  default: (...args: unknown[]) => {
    mockUseExperimentsList(...args);
    return mockProjectExperiments;
  },
}));

vi.mock("@/api/datasets/useExperimenstByIds", () => ({
  default: (params: { experimentsIds: string[] }) => {
    mockUseExperimentsByIds(params);
    return params.experimentsIds.map((id) => ({
      data: { id, name: id },
      isPending: false,
    }));
  },
}));

vi.mock("@/shared/Loader/Loader", () => ({
  default: () => <div data-testid="loader" />,
}));

vi.mock(
  "@/v2/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails",
  () => ({
    default: ({ experimentsIds }: { experimentsIds: string[] }) => (
      <div data-testid="details" data-ids={experimentsIds.join(",")} />
    ),
  }),
);
vi.mock(
  "@/v2/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab",
  () => ({
    default: ({ experimentsIds }: { experimentsIds: string[] }) => (
      <div data-testid="items-tab" data-ids={experimentsIds.join(",")} />
    ),
  }),
);
vi.mock(
  "@/v2/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab",
  () => ({ default: () => null }),
);
vi.mock(
  "@/v2/pages/CompareExperimentsPage/ExperimentFeedbackScoresTab/ExperimentFeedbackScoresTab",
  () => ({ default: () => null }),
);
vi.mock(
  "@/v2/pages/CompareExperimentsPage/ExperimentInsightsTab/ExperimentInsightsTab",
  () => ({ default: () => null }),
);
vi.mock(
  "@/v2/pages/CompareExperimentsPage/ExperimentLogsTab/ExperimentLogsTab",
  () => ({ default: () => null }),
);

describe("CompareExperimentsPage", () => {
  beforeEach(() => {
    mockUseExperimentsList.mockClear();
    mockUseExperimentsByIds.mockClear();
    mockRequestedIds = ["exp-a", "exp-b"];
    mockActiveProjectId = "proj-a";
    mockProjectExperiments = { isLoading: false };
  });

  it("asks the project-scoped list which experiments belong to the project", () => {
    mockProjectExperiments = {
      data: { content: [{ id: "exp-a" }, { id: "exp-b" }] },
      isLoading: false,
    };

    render(<CompareExperimentsPage />);

    expect(mockUseExperimentsList).toHaveBeenCalledWith(
      {
        projectId: "proj-a",
        experimentIds: ["exp-a", "exp-b"],
        types: Object.values(EXPERIMENT_TYPE),
        page: 1,
        size: 2,
      },
      { enabled: true },
    );
    expect(screen.getByTestId("details").dataset.ids).toBe("exp-a,exp-b");
    expect(screen.getByTestId("items-tab").dataset.ids).toBe("exp-a,exp-b");
  });

  it("drops experiments that are not listed in the project, keeping URL order", () => {
    mockRequestedIds = ["exp-b", "exp-foreign", "exp-a"];
    mockProjectExperiments = {
      data: { content: [{ id: "exp-a" }, { id: "exp-b" }] },
      isLoading: false,
    };

    render(<CompareExperimentsPage />);

    expect(mockUseExperimentsByIds).toHaveBeenLastCalledWith({
      experimentsIds: ["exp-b", "exp-a"],
    });
    expect(screen.getByTestId("details").dataset.ids).toBe("exp-b,exp-a");
    expect(screen.getByTestId("items-tab").dataset.ids).toBe("exp-b,exp-a");
  });

  it("shows the requested experiments when membership cannot be checked", () => {
    mockRequestedIds = ["exp-a", "exp-foreign"];
    mockProjectExperiments = { isLoading: false, isError: true };

    render(<CompareExperimentsPage />);

    expect(mockUseExperimentsByIds).toHaveBeenLastCalledWith({
      experimentsIds: ["exp-a", "exp-foreign"],
    });
    expect(screen.getByTestId("details").dataset.ids).toBe("exp-a,exp-foreign");
  });

  it("shows nothing when no requested experiment is in the project", () => {
    mockProjectExperiments = { data: { content: [] }, isLoading: false };

    render(<CompareExperimentsPage />);

    expect(mockUseExperimentsByIds).toHaveBeenLastCalledWith({
      experimentsIds: [],
    });
    expect(screen.getByTestId("details").dataset.ids).toBe("");
  });

  it("shows a loader until project membership is known", () => {
    mockProjectExperiments = { isLoading: true };

    render(<CompareExperimentsPage />);

    expect(screen.getByTestId("loader")).toBeTruthy();
    expect(screen.queryByTestId("details")).toBeNull();
  });

  it("does not query membership when the URL has no experiments", () => {
    mockRequestedIds = undefined;

    render(<CompareExperimentsPage />);

    expect(mockUseExperimentsList).toHaveBeenCalledWith(
      expect.objectContaining({ experimentIds: [] }),
      { enabled: false },
    );
  });
});
