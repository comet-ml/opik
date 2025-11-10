import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
  ANNOTATION_QUEUE_KEY,
  TRACES_KEY,
  THREADS_KEY,
} from "@/api/api";

type UseAnnotationQueueAddItemsMutationParams = {
  annotationQueueId: string;
  ids: string[];
};

const useAnnotationQueueAddItemsMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      annotationQueueId,
      ids,
    }: UseAnnotationQueueAddItemsMutationParams) => {
      const { data } = await api.post(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}/items/add`,
        { ids },
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
        description: `Failed to add items to annotation queue: ${message}`,
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      return Promise.all([
        queryClient.invalidateQueries({
          queryKey: [ANNOTATION_QUEUES_KEY],
        }),
        queryClient.invalidateQueries({
          queryKey: [
            ANNOTATION_QUEUE_KEY,
            { annotationQueueId: variables.annotationQueueId },
          ],
        }),
        queryClient.invalidateQueries({ queryKey: [TRACES_KEY] }),
        queryClient.invalidateQueries({ queryKey: [THREADS_KEY] }),
      ]);
    },
  });
};

export default useAnnotationQueueAddItemsMutation;
