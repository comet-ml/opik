import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseThreadStatusMutationParams = {
  threadId: string;
  projectId: string;
};

const useThreadCloseStatusMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      threadId,
      projectId,
    }: UseThreadStatusMutationParams) => {
      const { data } = await api.put(`${TRACES_REST_ENDPOINT}threads/close`, {
        thread_id: threadId,
        project_id: projectId,
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
        queryKey: [THREADS_KEY, variables],
      });
    },
  });
};

export default useThreadCloseStatusMutation;
