import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import DatasetExportPanel from "./DatasetExportPanel";
import { ReactNode } from "react";
import { DATASET_EXPORT_STATUS, DatasetExportJob } from "@/types/datasets";

// Mock functions that can be accessed in tests
const mockMarkAsViewed = vi.fn();
const mockToast = vi.fn();

// Mock the API hooks
vi.mock("@/api/datasets/useDatasetExportJob", () => ({
  default: vi.fn(() => ({
    data: undefined,
  })),
}));

vi.mock("@/api/datasets/useDatasetExportJobs", () => ({
  default: vi.fn(() => ({
    data: undefined,
  })),
}));

vi.mock("@/api/datasets/useMarkExportJobViewedMutation", () => ({
  default: () => ({
    mutate: mockMarkAsViewed,
  }),
}));

// Mock the toast hook
vi.mock("@/components/ui/use-toast", () => ({
  useToast: () => ({
    toast: mockToast,
  }),
}));

// Mock the store - we need to provide a real implementation for testing
const createMockStore = () => {
  let activeJobs = new Map<
    string,
    { job: DatasetExportJob; datasetName: string }
  >();
  let isPanelExpanded = true;
  let isHydrated = false;

  return {
    getState: () => ({
      activeJobs,
      isPanelExpanded,
      isHydrated,
    }),
    setState: (
      newState: Partial<{
        activeJobs: Map<string, { job: DatasetExportJob; datasetName: string }>;
        isPanelExpanded: boolean;
        isHydrated: boolean;
      }>,
    ) => {
      if (newState.activeJobs !== undefined) activeJobs = newState.activeJobs;
      if (newState.isPanelExpanded !== undefined)
        isPanelExpanded = newState.isPanelExpanded;
      if (newState.isHydrated !== undefined) isHydrated = newState.isHydrated;
    },
    reset: () => {
      activeJobs = new Map();
      isPanelExpanded = true;
      isHydrated = false;
    },
  };
};

const mockStore = createMockStore();

vi.mock("@/store/DatasetExportStore", () => ({
  useActiveExportJobs: () =>
    Array.from(mockStore.getState().activeJobs.values()),
  useHasActiveExportJobs: () => mockStore.getState().activeJobs.size > 0,
  useIsPanelExpanded: () => mockStore.getState().isPanelExpanded,
  useTogglePanelExpanded: () => () =>
    mockStore.setState({
      isPanelExpanded: !mockStore.getState().isPanelExpanded,
    }),
  useRemoveExportJob: () => (jobId: string) => {
    const newJobs = new Map(mockStore.getState().activeJobs);
    newJobs.delete(jobId);
    mockStore.setState({ activeJobs: newJobs });
  },
  useUpdateExportJob: () => (job: DatasetExportJob) => {
    const existing = mockStore.getState().activeJobs.get(job.id);
    if (!existing) return;
    const newJobs = new Map(mockStore.getState().activeJobs);
    newJobs.set(job.id, { ...existing, job });
    mockStore.setState({ activeJobs: newJobs });
  },
  useHydrateFromApi: () => vi.fn(),
  useIsHydrated: () => mockStore.getState().isHydrated,
  ExportJobInfo: {},
}));

describe("DatasetExportPanel", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
    mockStore.reset();
  });

  afterEach(() => {
    mockStore.reset();
  });

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{children}</TooltipProvider>
    </QueryClientProvider>
  );

  const createMockJob = (
    overrides: Partial<DatasetExportJob> = {},
  ): DatasetExportJob => ({
    id: "job-1",
    dataset_id: "dataset-1",
    dataset_name: "Test Dataset",
    status: DATASET_EXPORT_STATUS.PENDING,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    ...overrides,
  });

  describe("Panel rendering", () => {
    it("should not render when there are no active jobs", () => {
      render(<DatasetExportPanel />, { wrapper });

      expect(screen.queryByText("Preparing download")).not.toBeInTheDocument();
      expect(screen.queryByText("Download ready")).not.toBeInTheDocument();
    });

    it("should render panel when there are active jobs", () => {
      const job = createMockJob();
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "Test Dataset" }]]),
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("Preparing download")).toBeInTheDocument();
    });

    it("should show 'Download ready' when all jobs are completed", () => {
      const job = createMockJob({ status: DATASET_EXPORT_STATUS.COMPLETED });
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "Test Dataset" }]]),
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("Download ready")).toBeInTheDocument();
    });

    it("should display dataset name for each job", () => {
      const job = createMockJob();
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "My Dataset" }]]),
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("My Dataset")).toBeInTheDocument();
    });

    it("should show 'Exporting...' status for pending jobs", () => {
      const job = createMockJob({ status: DATASET_EXPORT_STATUS.PENDING });
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "Test Dataset" }]]),
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("Exporting...")).toBeInTheDocument();
    });

    it("should show 'Ready' status for completed jobs", () => {
      const job = createMockJob({ status: DATASET_EXPORT_STATUS.COMPLETED });
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "Test Dataset" }]]),
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("Ready")).toBeInTheDocument();
    });

    it("should show 'Failed' status for failed jobs", () => {
      const job = createMockJob({ status: DATASET_EXPORT_STATUS.FAILED });
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "Test Dataset" }]]),
        isHydrated: true, // Mark as hydrated to prevent toast from initial load
      });

      // Mark the job as already viewed to prevent toast
      job.viewed_at = "2024-01-01T00:00:02Z";
      mockStore.setState({
        activeJobs: new Map([["job-1", { job, datasetName: "Test Dataset" }]]),
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("Failed")).toBeInTheDocument();
    });
  });

  describe("Toast notifications for failed jobs", () => {
    it("should show toast for failed job that has not been viewed", async () => {
      const failedJob = createMockJob({
        id: "failed-job-1",
        status: DATASET_EXPORT_STATUS.FAILED,
        error_message: "Export failed due to timeout",
        viewed_at: undefined, // Not viewed yet
      });

      mockStore.setState({
        activeJobs: new Map([
          ["failed-job-1", { job: failedJob, datasetName: "Failed Dataset" }],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledWith({
          title: "Export failed",
          description: "Export failed due to timeout",
          variant: "destructive",
        });
      });
    });

    it("should call markAsViewed mutation when showing toast for failed job", async () => {
      const failedJob = createMockJob({
        id: "failed-job-2",
        status: DATASET_EXPORT_STATUS.FAILED,
        error_message: "Network error",
        viewed_at: undefined,
      });

      mockStore.setState({
        activeJobs: new Map([
          ["failed-job-2", { job: failedJob, datasetName: "Test Dataset" }],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      await waitFor(() => {
        expect(mockMarkAsViewed).toHaveBeenCalledWith({
          jobId: "failed-job-2",
        });
      });
    });

    it("should NOT show toast for failed job that has already been viewed", async () => {
      const viewedFailedJob = createMockJob({
        id: "viewed-failed-job",
        status: DATASET_EXPORT_STATUS.FAILED,
        error_message: "Some error",
        viewed_at: "2024-01-01T00:00:02Z", // Already viewed
      });

      mockStore.setState({
        activeJobs: new Map([
          [
            "viewed-failed-job",
            { job: viewedFailedJob, datasetName: "Test Dataset" },
          ],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      // Wait a bit to ensure no toast is called
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 100));
      });

      expect(mockToast).not.toHaveBeenCalled();
      expect(mockMarkAsViewed).not.toHaveBeenCalled();
    });

    it("should use fallback message when error_message is not provided", async () => {
      const failedJob = createMockJob({
        id: "failed-no-message",
        status: DATASET_EXPORT_STATUS.FAILED,
        error_message: undefined,
        viewed_at: undefined,
      });

      mockStore.setState({
        activeJobs: new Map([
          [
            "failed-no-message",
            { job: failedJob, datasetName: "My Dataset Name" },
          ],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledWith({
          title: "Export failed",
          description: 'Failed to export dataset "My Dataset Name"',
          variant: "destructive",
        });
      });
    });
  });

  describe("Toast deduplication", () => {
    it("should not show toast again after viewed_at is set", async () => {
      // First render with unviewed failed job
      const failedJob = createMockJob({
        id: "dedup-job",
        status: DATASET_EXPORT_STATUS.FAILED,
        error_message: "Error",
        viewed_at: undefined,
      });

      mockStore.setState({
        activeJobs: new Map([
          ["dedup-job", { job: failedJob, datasetName: "Test Dataset" }],
        ]),
        isHydrated: true,
      });

      const { unmount } = render(<DatasetExportPanel />, { wrapper });

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledTimes(1);
      });

      // Simulate what happens after markAsViewed is called - the viewed_at is set
      unmount();
      vi.clearAllMocks();

      // Now render again with viewed_at set (simulating page refresh after job was marked as viewed)
      const viewedJob = createMockJob({
        id: "dedup-job",
        status: DATASET_EXPORT_STATUS.FAILED,
        error_message: "Error",
        viewed_at: "2024-01-01T00:00:02Z", // Now viewed
      });

      mockStore.setState({
        activeJobs: new Map([
          ["dedup-job", { job: viewedJob, datasetName: "Test Dataset" }],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      // Wait a bit and verify toast was not called since job is already viewed
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 100));
      });

      expect(mockToast).not.toHaveBeenCalled();
    });
  });

  describe("Multiple jobs handling", () => {
    it("should render multiple jobs correctly", () => {
      const pendingJob = createMockJob({
        id: "pending-job",
        status: DATASET_EXPORT_STATUS.PENDING,
      });
      const completedJob = createMockJob({
        id: "completed-job",
        status: DATASET_EXPORT_STATUS.COMPLETED,
      });

      mockStore.setState({
        activeJobs: new Map([
          ["pending-job", { job: pendingJob, datasetName: "Pending Dataset" }],
          [
            "completed-job",
            { job: completedJob, datasetName: "Completed Dataset" },
          ],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      expect(screen.getByText("Pending Dataset")).toBeInTheDocument();
      expect(screen.getByText("Completed Dataset")).toBeInTheDocument();
      expect(screen.getByText("Exporting...")).toBeInTheDocument();
      expect(screen.getByText("Ready")).toBeInTheDocument();
    });

    it("should show 'Preparing download' when there are pending jobs mixed with completed", () => {
      const pendingJob = createMockJob({
        id: "pending-job",
        status: DATASET_EXPORT_STATUS.PENDING,
      });
      const completedJob = createMockJob({
        id: "completed-job",
        status: DATASET_EXPORT_STATUS.COMPLETED,
      });

      mockStore.setState({
        activeJobs: new Map([
          ["pending-job", { job: pendingJob, datasetName: "Pending Dataset" }],
          [
            "completed-job",
            { job: completedJob, datasetName: "Completed Dataset" },
          ],
        ]),
        isHydrated: true,
      });

      render(<DatasetExportPanel />, { wrapper });

      // Should show "Preparing download" because there's at least one pending job
      expect(screen.getByText("Preparing download")).toBeInTheDocument();
    });
  });
});
