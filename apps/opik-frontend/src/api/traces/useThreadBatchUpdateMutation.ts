import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseThreadBatchUpdateMutationParams = {
  projectId: string;
  threadIds: string[];
  thread: {
    tags?: string[];
  };
  mergeTags?: boolean;
};

const useThreadBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      threadIds,
      thread,
      mergeTags,
    }: UseThreadBatchUpdateMutationParams) => {
      const { data } = await api.patch(TRACES_REST_ENDPOINT + "threads/batch", {
        ids: threadIds,
        update: thread,
        merge_tags: mergeTags,
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [THREADS_KEY, { projectId: variables.projectId }],
      });
    },
  });
};

export default useThreadBatchUpdateMutation;
