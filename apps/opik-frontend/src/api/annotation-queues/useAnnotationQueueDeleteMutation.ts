import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { ANNOTATION_QUEUES_KEY, ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueuesBatchDelete } from "@/types/annotation-queues";
import { useToast } from "@/components/ui/use-toast";

type UseAnnotationQueueDeleteMutationParams = {
  ids: string[];
};

const useAnnotationQueueDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseAnnotationQueueDeleteMutationParams) => {
      const { data } = await api.post(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}delete`,
        {
          ids: ids,
        } as AnnotationQueuesBatchDelete,
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
      return queryClient.invalidateQueries({
        queryKey: [ANNOTATION_QUEUES_KEY],
      });
    },
  });
};

export default useAnnotationQueueDeleteMutation;

