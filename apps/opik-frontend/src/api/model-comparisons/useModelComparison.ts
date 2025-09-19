import { useQuery, useMutation, useQueryClient, QueryFunctionContext } from "@tanstack/react-query";
import api, { QueryConfig } from "@/api/api";
import { ModelComparison } from "./useModelComparisonsList";

type UseModelComparisonParams = {
  id: string;
};

const getModelComparison = async (
  { signal }: QueryFunctionContext,
  params: UseModelComparisonParams,
) => {
  const { data } = await api.get<ModelComparison>(`/api/v1/model-comparisons/${params.id}`, {
    signal,
  });
  return data;
};

export default function useModelComparison(
  params: UseModelComparisonParams,
  options?: QueryConfig<ModelComparison>,
) {
  return useQuery({
    queryKey: ["model-comparison", params.id],
    queryFn: (context) => getModelComparison(context, params),
    enabled: !!params.id,
    ...options,
  });
}

// Create model comparison mutation
export function useCreateModelComparison() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: Omit<ModelComparison, "id" | "created_at" | "last_updated_at">) => {
      const response = await api.post<ModelComparison>("/api/v1/model-comparisons", data);
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["model-comparisons"] });
    },
  });
}

// Update model comparison mutation
export function useUpdateModelComparison() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Partial<ModelComparison> }) => {
      const response = await api.put<ModelComparison>(`/api/v1/model-comparisons/${id}`, data);
      return response.data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["model-comparisons"] });
      queryClient.invalidateQueries({ queryKey: ["model-comparison", data.id] });
    },
  });
}

// Delete model comparison mutation
export function useDeleteModelComparison() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/api/v1/model-comparisons/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["model-comparisons"] });
    },
  });
}

// Run analysis mutation
export function useRunModelComparisonAnalysis() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const response = await api.post<ModelComparison>(`/api/v1/model-comparisons/${id}/analyze`);
      return response.data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["model-comparisons"] });
      queryClient.invalidateQueries({ queryKey: ["model-comparison", data.id] });
    },
  });
}

// Get available models
export function useAvailableModels() {
  return useQuery({
    queryKey: ["available-models"],
    queryFn: async () => {
      const { data } = await api.get<Array<{ name: string; provider: string; trace_count: number }>>(
        "/api/v1/model-comparisons/available-models"
      );
      return data;
    },
  });
}

// Get available datasets
export function useAvailableDatasets() {
  return useQuery({
    queryKey: ["available-datasets"],
    queryFn: async () => {
      const { data } = await api.get<Array<{ name: string; id: string; experiment_count: number }>>(
        "/api/v1/model-comparisons/available-datasets"
      );
      return data;
    },
  });
}