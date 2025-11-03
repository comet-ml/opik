import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useAddTracesToDatasetMutation from "./useAddTracesToDatasetMutation";
import api from "@/api/api";
import { createElement } from "react";

// Mock the API module
vi.mock("@/api/api", () => ({
  default: {
    post: vi.fn(),
  },
  DATASETS_REST_ENDPOINT: "/v1/private/datasets/",
}));

// Mock the toast hook
vi.mock("@/components/ui/use-toast", () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}));

describe("useAddTracesToDatasetMutation", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  const createWrapper = () => {
    const Wrapper = ({ children }: { children: React.ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
    Wrapper.displayName = "QueryClientWrapper";
    return Wrapper;
  };

  it("should successfully add traces to dataset with enrichment options", async () => {
    const mockResponse = { data: {} };
    vi.mocked(api.post).mockResolvedValueOnce(mockResponse);

    const { result } = renderHook(() => useAddTracesToDatasetMutation(), {
      wrapper: createWrapper(),
    });

    const datasetId = "dataset-123";
    const traceIds = ["trace-1", "trace-2", "trace-3"];
    const enrichmentOptions = {
      include_spans: true,
      include_tags: true,
      include_feedback_scores: true,
      include_comments: true,
      include_usage: true,
      include_metadata: true,
    };

    result.current.mutate({
      datasetId,
      traceIds,
      enrichmentOptions,
      workspaceName: "test-workspace",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith(
      "/v1/private/datasets/dataset-123/items/from-traces",
      {
        trace_ids: traceIds,
        enrichment_options: enrichmentOptions,
        workspace_name: "test-workspace",
      },
    );
  });

  it("should handle partial enrichment options", async () => {
    const mockResponse = { data: {} };
    vi.mocked(api.post).mockResolvedValueOnce(mockResponse);

    const { result } = renderHook(() => useAddTracesToDatasetMutation(), {
      wrapper: createWrapper(),
    });

    const enrichmentOptions = {
      include_spans: true,
      include_tags: false,
      include_feedback_scores: true,
      include_comments: false,
      include_usage: false,
      include_metadata: true,
    };

    result.current.mutate({
      datasetId: "dataset-456",
      traceIds: ["trace-1"],
      enrichmentOptions,
      workspaceName: "test-workspace",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith(
      "/v1/private/datasets/dataset-456/items/from-traces",
      expect.objectContaining({
        enrichment_options: enrichmentOptions,
      }),
    );
  });

  it("should handle API errors", async () => {
    const mockError = {
      response: {
        data: {
          message: "Dataset not found",
        },
      },
    };
    vi.mocked(api.post).mockRejectedValueOnce(mockError);

    const { result } = renderHook(() => useAddTracesToDatasetMutation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      datasetId: "non-existent",
      traceIds: ["trace-1"],
      enrichmentOptions: {
        include_spans: true,
        include_tags: true,
        include_feedback_scores: true,
        include_comments: true,
        include_usage: true,
        include_metadata: true,
      },
      workspaceName: "test-workspace",
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("should invalidate queries on success", async () => {
    const mockResponse = { data: {} };
    vi.mocked(api.post).mockResolvedValueOnce(mockResponse);

    const invalidateQueriesSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useAddTracesToDatasetMutation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      datasetId: "dataset-789",
      traceIds: ["trace-1"],
      enrichmentOptions: {
        include_spans: false,
        include_tags: false,
        include_feedback_scores: false,
        include_comments: false,
        include_usage: false,
        include_metadata: false,
      },
      workspaceName: "test-workspace",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({
      queryKey: ["dataset-items", { datasetId: "dataset-789" }],
    });
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({
      queryKey: ["datasets"],
    });
  });

  it("should handle empty trace IDs array", async () => {
    const mockResponse = { data: {} };
    vi.mocked(api.post).mockResolvedValueOnce(mockResponse);

    const { result } = renderHook(() => useAddTracesToDatasetMutation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      datasetId: "dataset-123",
      traceIds: [],
      enrichmentOptions: {
        include_spans: true,
        include_tags: true,
        include_feedback_scores: true,
        include_comments: true,
        include_usage: true,
        include_metadata: true,
      },
      workspaceName: "test-workspace",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith(
      "/v1/private/datasets/dataset-123/items/from-traces",
      expect.objectContaining({
        trace_ids: [],
      }),
    );
  });
});
