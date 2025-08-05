import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { TRACES_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type DeleteTraceFeedbackScoreParams = {
  traceId: string;
  scoreId: string;
  workspaceName: string;
};

const deleteTraceFeedbackScore = async (params: DeleteTraceFeedbackScoreParams) => {
  const { data } = await api.delete(
    `/v1/private/traces/${params.traceId}/feedback-scores/${params.scoreId}`,
    {
      headers: {
        "X-Workspace-Name": params.workspaceName,
      },
    },
  );
  return data;
};

export default function useDeleteTraceFeedbackScore() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: deleteTraceFeedbackScore,
    onSuccess: (_, variables) => {
      // Invalidate the feedback score groups query
      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY, "feedback-score-groups", { traceId: variables.traceId, workspaceName: variables.workspaceName }],
      });
      
      // Also invalidate the main trace query to refresh the trace data
      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY, { id: variables.traceId }],
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