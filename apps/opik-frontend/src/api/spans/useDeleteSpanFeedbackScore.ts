import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { SPANS_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type DeleteSpanFeedbackScoreParams = {
  spanId: string;
  scoreId: string;
  workspaceName: string;
};

const deleteSpanFeedbackScore = async (params: DeleteSpanFeedbackScoreParams) => {
  const { data } = await api.delete(
    `/v1/private/spans/${params.spanId}/feedback-scores/${params.scoreId}`,
    {
      headers: {
        "X-Workspace-Name": params.workspaceName,
      },
    },
  );
  return data;
};

export default function useDeleteSpanFeedbackScore() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: deleteSpanFeedbackScore,
    onSuccess: (_, variables) => {
      // Invalidate the feedback score groups query
      queryClient.invalidateQueries({
        queryKey: [SPANS_KEY, "feedback-score-groups", { spanId: variables.spanId, workspaceName: variables.workspaceName }],
      });
      
      // Also invalidate the main span query to refresh the span data
      queryClient.invalidateQueries({
        queryKey: [SPANS_KEY, { id: variables.spanId }],
      });

      toast({
        description: "Feedback score deleted successfully",
      });
    },
    onError: () => {
      toast({
        description: "Failed to delete feedback score",
        variant: "destructive",
      });
    },
  });
}