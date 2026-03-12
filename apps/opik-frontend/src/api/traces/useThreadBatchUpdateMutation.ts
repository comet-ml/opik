import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import {
  TagUpdateFields,
  buildTagUpdatePayload,
  extractErrorMessage,
} from "@/lib/tags";

type UseThreadBatchUpdateMutationParams = {
  projectId: string;
  threadIds: string[];
  thread: TagUpdateFields;
};

const useThreadBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      threadIds,
      thread,
    }: UseThreadBatchUpdateMutationParams) => {
      const { data } = await api.patch(TRACES_REST_ENDPOINT + "threads/batch", {
        ids: threadIds,
        update: buildTagUpdatePayload(thread),
      });

      return data;
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: extractErrorMessage(error),
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
