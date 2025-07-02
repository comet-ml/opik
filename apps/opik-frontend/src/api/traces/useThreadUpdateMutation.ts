import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { Thread } from "@/types/traces";

type ThreadUpdateData = Pick<Thread, "tags">;

type UseThreadUpdateMutationParams = {
  data: Partial<ThreadUpdateData>;
  threadId: string;
  projectId: string;
};

const useThreadUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ data, threadId }: UseThreadUpdateMutationParams) => {
      const { data: updatedThread } = await api.patch(
        `${TRACES_REST_ENDPOINT}${THREADS_KEY}/${threadId}`,
        data,
      );

      return updatedThread;
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [THREADS_KEY, { projectId: variables.projectId }],
      });
    },
  });
};

export default useThreadUpdateMutation;
