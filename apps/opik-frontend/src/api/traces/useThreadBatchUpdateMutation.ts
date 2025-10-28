import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseThreadBatchUpdateMutationParams = {
  threadModelIds: string[];
  tags: string[];
};

const useThreadBatchUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      threadModelIds,
      tags,
    }: UseThreadBatchUpdateMutationParams) => {
      const { data } = await api.patch(`${TRACES_REST_ENDPOINT}threads/batch`, {
        thread_model_ids: threadModelIds,
        update: {
          tags,
        },
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
        description: `Failed to update threads: ${message}`,
        variant: "destructive",
      });
    },
    onSuccess: () => {
      toast({
        title: "Threads updated",
        description: "Tags have been successfully updated",
      });

      return queryClient.invalidateQueries({
        queryKey: [TRACES_REST_ENDPOINT, "threads"],
      });
    },
  });
};

export default useThreadBatchUpdateMutation;
