import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import { useToast } from "@/ui/use-toast";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
  TRACES_KEY,
  TRACE_KEY,
  THREADS_KEY,
} from "@/api/api";

type UseAnnotationQueueBatchDeleteMutationParams = {
  ids: string[];
};

const useAnnotationQueueBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
    }: UseAnnotationQueueBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}delete`,
        {
          ids: ids,
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
    onSettled: () => {
      // Traces and threads lists render queue names, so a deleted queue must drop out of them too
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: [ANNOTATION_QUEUES_KEY] }),
        queryClient.invalidateQueries({ queryKey: [TRACES_KEY] }),
        queryClient.invalidateQueries({ queryKey: [TRACE_KEY] }),
        queryClient.invalidateQueries({ queryKey: [THREADS_KEY] }),
      ]);
    },
  });
};

export default useAnnotationQueueBatchDeleteMutation;
