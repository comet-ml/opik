import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
} from "@/api/api";
import {
  CreateAnnotationQueue,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { extractIdFromLocation } from "@/lib/utils";

type UseAnnotationQueueCreateMutationParams = {
  annotationQueue: CreateAnnotationQueue;
  withResponse?: boolean;
};

const useAnnotationQueueCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      annotationQueue,
      withResponse,
    }: UseAnnotationQueueCreateMutationParams) => {
      const { data, headers } = await api.post<AnnotationQueue>(
        ANNOTATION_QUEUES_REST_ENDPOINT,
        annotationQueue,
      );

      const extractedId = extractIdFromLocation(headers?.location);
      if (extractedId && withResponse) {
        const { data: annotationQueueData } = await api.get<AnnotationQueue>(
          `${ANNOTATION_QUEUES_REST_ENDPOINT}${extractedId}`,
        );

        return annotationQueueData;
      }

      return data
        ? data
        : {
            ...annotationQueue,
            id: extractedId,
          };
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
      return queryClient.invalidateQueries({
        queryKey: [ANNOTATION_QUEUES_KEY],
      });
    },
  });
};

export default useAnnotationQueueCreateMutation;
