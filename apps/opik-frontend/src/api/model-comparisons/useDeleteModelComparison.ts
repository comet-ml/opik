import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/api/api";

interface DeleteModelComparisonResponse {
  success: boolean;
  message: string;
}

export const useDeleteModelComparison = () => {
  const queryClient = useQueryClient();

  return useMutation<DeleteModelComparisonResponse, Error, string>({
    mutationFn: async (comparisonId: string) => {
      const response = await api.delete(`/v1/model-comparisons/${comparisonId}`);
      return response.data;
    },
    onSuccess: () => {
      // Invalidate and refetch the comparisons list
      queryClient.invalidateQueries({ queryKey: ["model-comparisons"] });
    },
  });
};