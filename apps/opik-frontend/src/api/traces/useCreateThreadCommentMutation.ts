import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseCreateThreadCommentMutationParams = {
  threadId: string;
  projectId: string;
  text: string;
};

const useCreateThreadCommentMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      text,
      threadId,
    }: UseCreateThreadCommentMutationParams) => {
      const { data } = await api.post(
        `${TRACES_REST_ENDPOINT}threads/${threadId}/comments`,
        { text },
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
    },
  });
};

export default useCreateThreadCommentMutation;
