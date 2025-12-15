import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

type UseThreadFeedbackScoreSetMutationScore = {
  categoryName?: string;
  name: string;
  value: number;
  reason?: string;
};

type UseThreadFeedbackScoreSetMutationParams = {
  threadId: string;
  projectName: string;
  projectId: string;
  scores: UseThreadFeedbackScoreSetMutationScore[];
};

const useThreadFeedbackScoreSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      threadId,
      projectName,
      scores,
    }: UseThreadFeedbackScoreSetMutationParams) => {
      const { data } = await api.put(
        `${TRACES_REST_ENDPOINT}threads/feedback-scores`,
        {
          scores: scores.map(({ categoryName, name, value, reason }) => ({
            category_name: categoryName,
            thread_id: threadId,
            project_name: projectName,
            name,
            source: FEEDBACK_SCORE_TYPE.ui,
            value,
            reason,
          })),
        },
      );

      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: async (data, error, variables) => {
      await queryClient.invalidateQueries({
        queryKey: [THREADS_KEY, { projectId: variables.projectId }],
      });
      await queryClient.invalidateQueries({
        queryKey: ["threads-columns", { projectId: variables.projectId }],
      });
      await queryClient.invalidateQueries({
        queryKey: ["threads-statistic", { projectId: variables.projectId }],
      });
    },
  });
};

export default useThreadFeedbackScoreSetMutation;
