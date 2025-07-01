import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";

type UseThreadCommentsBatchDeleteMutationParams = {
  ids: string[];
  threadId: string;
  projectId: string;
};

const useThreadCommentsBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
      threadId,
      projectId,
    }: UseThreadCommentsBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${TRACES_REST_ENDPOINT}threads/comments/delete`,
        {
          ids: ids,
          thread_id: threadId,
          project_id: projectId,
        },
      );
      return data;
    },
    onError: (error) => {
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
    },
  });
};

export default useThreadCommentsBatchDeleteMutation;
