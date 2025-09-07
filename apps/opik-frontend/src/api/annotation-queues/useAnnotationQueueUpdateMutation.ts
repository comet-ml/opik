import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  ANNOTATION_QUEUES_KEY,
  ANNOTATION_QUEUE_KEY,
  ANNOTATION_QUEUES_REST_ENDPOINT,
} from "@/api/api";
import { AnnotationQueueUpdate } from "@/types/annotation-queues";
import { useToast } from "@/components/ui/use-toast";

type UseAnnotationQueueUpdateMutationParams = {
  annotationQueueId: string;
  annotationQueue: AnnotationQueueUpdate;
};

const useAnnotationQueueUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      annotationQueueId,
      annotationQueue,
    }: UseAnnotationQueueUpdateMutationParams) => {
      const { data } = await api.patch(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}`,
        annotationQueue,
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [
          ANNOTATION_QUEUE_KEY,
          { annotationQueueId: variables.annotationQueueId },
        ],
      });
      return queryClient.invalidateQueries({
        queryKey: [ANNOTATION_QUEUES_KEY],
      });
    },
  });
};

export default useAnnotationQueueUpdateMutation;
