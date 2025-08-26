import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { vi, describe, it, expect, beforeEach } from "vitest";
import AnnotationQueuesPage from "./AnnotationQueuesPage";
import * as useAnnotationQueuesList from "@/api/annotationQueues/useAnnotationQueuesList";
import * as useAnnotationQueueDeleteMutation from "@/api/annotationQueues/useAnnotationQueueDeleteMutation";

// Mock the hooks
vi.mock("@/api/annotationQueues/useAnnotationQueuesList");
vi.mock("@/api/annotationQueues/useAnnotationQueueDeleteMutation");
vi.mock("@/hooks/useWorkspaceName", () => ({
  useWorkspaceName: () => "test-workspace",
}));

// Mock the router
vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => vi.fn(),
  useSearch: () => ({ page: 1, size: 10 }),
}));

// Mock UI components
vi.mock("@/components/ui/use-toast", () => ({
  toast: vi.fn(),
}));

const mockQueues = {
  data: [
    {
      id: "queue-1",
      name: "Test Queue 1",
      description: "First test queue",
      status: "active" as const,
      created_by: "admin",
      project_id: "project-1",
      visible_fields: ["input", "output"],
      required_metrics: ["rating"],
      optional_metrics: ["comment"],
      created_at: "2024-01-01T00:00:00Z",
      updated_at: "2024-01-01T00:00:00Z",
      total_items: 10,
      completed_items: 5,
      assigned_smes: ["sme1", "sme2"],
      share_url: "https://app.opik.ml/annotation/queue-1",
    },
    {
      id: "queue-2",
      name: "Test Queue 2",
      description: "Second test queue",
      status: "completed" as const,
      created_by: "admin",
      project_id: "project-1",
      visible_fields: ["input", "output"],
      required_metrics: ["rating"],
      optional_metrics: ["comment"],
      created_at: "2024-01-02T00:00:00Z",
      updated_at: "2024-01-02T00:00:00Z",
      total_items: 20,
      completed_items: 20,
      assigned_smes: ["sme1"],
      share_url: "https://app.opik.ml/annotation/queue-2",
    },
  ],
  total: 2,
};

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
};

describe("AnnotationQueuesPage", () => {
  const mockUseAnnotationQueuesList = vi.mocked(useAnnotationQueuesList.default);
  const mockUseAnnotationQueueDeleteMutation = vi.mocked(
    useAnnotationQueueDeleteMutation.default
  );

  beforeEach(() => {
    vi.clearAllMocks();

    // Default mock implementations
    mockUseAnnotationQueuesList.mockReturnValue({
      data: mockQueues,
      isLoading: false,
      error: null,
      refetch: vi.fn(),
    } as any);

    mockUseAnnotationQueueDeleteMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
  });

  it("renders the page title and create button", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    expect(screen.getByText("Annotation Queues")).toBeInTheDocument();
    expect(screen.getByText("Create Queue")).toBeInTheDocument();
  });

  it("displays annotation queues in a table", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    expect(screen.getByText("Test Queue 1")).toBeInTheDocument();
    expect(screen.getByText("Test Queue 2")).toBeInTheDocument();
    expect(screen.getByText("First test queue")).toBeInTheDocument();
    expect(screen.getByText("Second test queue")).toBeInTheDocument();
  });

  it("shows queue status badges", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Completed")).toBeInTheDocument();
  });

  it("displays progress information", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    // Check for progress text (5/10, 20/20)
    expect(screen.getByText("5 / 10")).toBeInTheDocument();
    expect(screen.getByText("20 / 20")).toBeInTheDocument();
  });

  it("shows SME count", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    expect(screen.getByText("2 SMEs")).toBeInTheDocument();
    expect(screen.getByText("1 SME")).toBeInTheDocument();
  });

  it("handles loading state", () => {
    mockUseAnnotationQueuesList.mockReturnValue({
      data: undefined,
      isLoading: true,
      error: null,
      refetch: vi.fn(),
    } as any);

    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    expect(screen.getByText("Loading annotation queues...")).toBeInTheDocument();
  });

  it("handles empty state", () => {
    mockUseAnnotationQueuesList.mockReturnValue({
      data: { data: [], total: 0 },
      isLoading: false,
      error: null,
      refetch: vi.fn(),
    } as any);

    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    expect(screen.getByText("No annotation queues found")).toBeInTheDocument();
    expect(
      screen.getByText("Create your first annotation queue to get started.")
    ).toBeInTheDocument();
  });

  it("opens create dialog when create button is clicked", async () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    const createButton = screen.getByText("Create Queue");
    fireEvent.click(createButton);

    await waitFor(() => {
      expect(screen.getByText("Create Annotation Queue")).toBeInTheDocument();
    });
  });

  it("shows action buttons for each queue", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    // Should have copy link, add items, view details, and delete buttons for each queue
    const copyButtons = screen.getAllByText("Copy share link");
    const addItemsButtons = screen.getAllByText("Add items");
    const viewDetailsButtons = screen.getAllByText("View details");
    const deleteButtons = screen.getAllByLabelText("Delete queue");

    expect(copyButtons).toHaveLength(2);
    expect(addItemsButtons).toHaveLength(2);
    expect(viewDetailsButtons).toHaveLength(2);
    expect(deleteButtons).toHaveLength(2);
  });

  it("copies share URL to clipboard", async () => {
    // Mock clipboard API
    const mockWriteText = vi.fn();
    Object.assign(navigator, {
      clipboard: {
        writeText: mockWriteText,
      },
    });

    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    const copyButtons = screen.getAllByText("Copy share link");
    fireEvent.click(copyButtons[0]);

    expect(mockWriteText).toHaveBeenCalledWith(
      "https://app.opik.ml/annotation/queue-1"
    );
  });

  it("handles search functionality", async () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    const searchInput = screen.getByPlaceholderText("Search queues...");
    fireEvent.change(searchInput, { target: { value: "Test Queue 1" } });

    // The search should trigger a refetch with the search term
    await waitFor(() => {
      expect(mockUseAnnotationQueuesList).toHaveBeenCalledWith(
        expect.objectContaining({
          search: "Test Queue 1",
        })
      );
    });
  });

  it("handles pagination", () => {
    render(<AnnotationQueuesPage />, { wrapper: createWrapper() });

    // Check if pagination controls are rendered (assuming they exist)
    // This would depend on the actual pagination implementation
    expect(mockUseAnnotationQueuesList).toHaveBeenCalledWith(
      expect.objectContaining({
        page: 1,
        size: 10,
      })
    );
  });
});