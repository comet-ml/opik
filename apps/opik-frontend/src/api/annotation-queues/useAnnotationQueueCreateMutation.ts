import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { ANNOTATION_QUEUES_KEY, ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueue } from "@/types/annotation-queues";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseAnnotationQueueCreateMutationParams = {
  annotationQueue: Partial<AnnotationQueue>;
};

const useAnnotationQueueCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ annotationQueue }: UseAnnotationQueueCreateMutationParams) => {
      const { data, headers } = await api.post(ANNOTATION_QUEUES_REST_ENDPOINT, {
        ...annotationQueue,
      });

      return data
        ? data
        : {
            ...annotationQueue,
            id: extractIdFromLocation(headers?.location),
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

