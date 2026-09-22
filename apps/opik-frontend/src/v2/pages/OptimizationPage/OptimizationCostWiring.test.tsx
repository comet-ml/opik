import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";

/**
 * OPIK-7521: the page must hand the backend's run-level total to the cost card.
 * That single prop is what makes the fix visible, and the card's own unit tests
 * cannot see it — without this, deleting the wiring leaves every test green.
 */

const kpiCardSpy = vi.fn();

vi.mock("./OptimizationKPICards", () => ({
  default: (props: Record<string, unknown>) => {
    kpiCardSpy(props);
    return null;
  },
}));

vi.mock("./useOptimizationData", () => ({
  useOptimizationData: () => ({
    optimizationId: "opt-1",
    optimization: {
      id: "opt-1",
      status: "completed",
      objective_name: "accuracy",
      created_at: "2026-07-30T10:00:00Z",
      last_updated_at: "2026-07-30T10:05:00Z",
      total_optimization_cost: 0.42,
    },
    experiments: [],
    candidates: [],
    rows: [],
    noDataText: "",
    sortableBy: [],
    bestCandidate: undefined,
    baselineCandidate: undefined,
    inProgressInfo: undefined,
    isRunningMiniBatches: false,
    isTestSuite: false,
    isOptimizationPending: false,
    isExperimentsPending: false,
    isExperimentsPlaceholderData: false,
    isExperimentsFetching: false,
    sortedColumns: [],
    setSortedColumns: vi.fn(),
    selectedColumns: [],
    setSelectedColumns: vi.fn(),
    columnsOrder: [],
    setColumnsOrder: vi.fn(),
    columnsWidth: {},
    setColumnsWidth: vi.fn(),
    height: "small",
    setHeight: vi.fn(),
    search: "",
    setSearch: vi.fn(),
    page: 1,
    setPage: vi.fn(),
    size: 10,
    setSize: vi.fn(),
    total: 0,
    handleRowClick: vi.fn(),
    handleRefresh: vi.fn(),
    trialSidebar: {
      open: false,
      openTrial: vi.fn(),
      close: vi.fn(),
      experimentIds: [],
      trialNumber: undefined,
    },
  }),
}));

vi.mock("./useOptimizationColumns", () => ({
  useOptimizationColumns: () => ({ columnsDef: [], columns: [] }),
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canUseOptimizationStudio: true } }),
}));

vi.mock("@/store/BreadcrumbsStore", () => ({
  default: () => vi.fn(),
}));

// Heavy children irrelevant to the wiring under test.
vi.mock("@/v2/pages-shared/experiments/OptimizationProgressChart", () => ({
  default: () => null,
}));
vi.mock("./OptimizationHeader", () => ({ default: () => null }));
vi.mock("./OptimizationTrialsControls", () => ({ default: () => null }));
vi.mock("./OptimizationTrialsTable", () => ({ default: () => null }));
vi.mock("./BestTrialPrompt", () => ({ default: () => null }));
vi.mock("./RunErrorPanel", () => ({ default: () => null }));
vi.mock("./EmptyRunWarningPanel", () => ({ default: () => null }));
vi.mock("./TrialSidebar/TrialSidebar", () => ({ default: () => null }));
vi.mock("./TrialSidebar/TrialSidebarContent", () => ({ default: () => null }));

import OptimizationPage from "./OptimizationPage";

describe("OptimizationPage cost wiring", () => {
  it("passes the backend total_optimization_cost to the KPI cards", () => {
    render(<OptimizationPage />);

    expect(kpiCardSpy).toHaveBeenCalled();
    const props = kpiCardSpy.mock.calls[0][0];
    expect(props.totalOptimizationCost).toBe(0.42);
  });
});
