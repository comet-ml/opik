import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { useLoggedInUserName } from "@/store/AppStore";

type UseThreadFeedbackScoreDeleteMutationParams = {
  names: string[];
  threadId: string;
  projectId: string;
  projectName: string;
  author?: string;
};

const useThreadFeedbackScoreDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const currentUserName = useLoggedInUserName();

  return useMutation({
    mutationFn: async ({
      names,
      threadId,
      projectName,
      author,
    }: UseThreadFeedbackScoreDeleteMutationParams) => {
      const endpoint = `${TRACES_REST_ENDPOINT}threads/feedback-scores/delete`;

      const { data } = await api.post(endpoint, {
        names,
        thread_id: threadId,
        project_name: projectName,
        author: author ?? currentUserName,
      });

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

export default useThreadFeedbackScoreDeleteMutation;
