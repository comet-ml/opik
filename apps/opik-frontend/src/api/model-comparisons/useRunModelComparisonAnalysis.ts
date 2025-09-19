import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/api/api";

interface RunModelComparisonAnalysisRequest {
  id: string;
}

interface RunModelComparisonAnalysisResponse {
  success: boolean;
  message: string;
}

export const useRunModelComparisonAnalysis = () => {
  const queryClient = useQueryClient();

  return useMutation<RunModelComparisonAnalysisResponse, Error, string>({
    mutationFn: async (comparisonId: string) => {
      const response = await api.post(`/v1/model-comparisons/${comparisonId}/analyze`);
      return response.data;
    },
    onSuccess: (data, comparisonId) => {
      // Invalidate and refetch the comparison data
      queryClient.invalidateQueries({ queryKey: ["modelComparison", comparisonId] });
      queryClient.invalidateQueries({ queryKey: ["modelComparisonsList"] });
    },
  });
};