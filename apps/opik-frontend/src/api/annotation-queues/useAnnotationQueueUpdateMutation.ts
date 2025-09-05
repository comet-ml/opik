import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
  ANNOTATION_QUEUE_KEY,
} from "@/api/api";
import {
  UpdateAnnotationQueue,
  AnnotationQueue,
} from "@/types/annotation-queues";

type UseAnnotationQueueUpdateMutationParams = {
  annotationQueue: UpdateAnnotationQueue & { id: string };
};

const useAnnotationQueueUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      annotationQueue,
    }: UseAnnotationQueueUpdateMutationParams) => {
      const { id, ...updateData } = annotationQueue;

      const { data } = await api.patch<AnnotationQueue>(
        ANNOTATION_QUEUES_REST_ENDPOINT + id,
        updateData,
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
          { annotationQueueId: variables.annotationQueue.id },
        ],
      });
      return queryClient.invalidateQueries({
        queryKey: [ANNOTATION_QUEUES_KEY],
      });
    },
  });
};

export default useAnnotationQueueUpdateMutation;
