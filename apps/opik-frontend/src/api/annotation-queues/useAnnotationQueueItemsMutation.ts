import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  ANNOTATION_QUEUES_KEY,
  ANNOTATION_QUEUE_KEY,
  ANNOTATION_QUEUES_REST_ENDPOINT,
  TRACES_KEY,
  SPANS_KEY,
} from "@/api/api";
import {
  AnnotationQueueItemsAdd,
  AnnotationQueueItemsDelete,
} from "@/types/annotation-queues";
import { useToast } from "@/components/ui/use-toast";

type UseAnnotationQueueItemsAddMutationParams = {
  annotationQueueId: string;
  itemIds: string[];
};

type UseAnnotationQueueItemsDeleteMutationParams = {
  annotationQueueId: string;
  itemIds: string[];
};

export const useAnnotationQueueItemsAddMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      annotationQueueId,
      itemIds,
    }: UseAnnotationQueueItemsAddMutationParams) => {
      const { data } = await api.post(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}/items/add`,
        {
          ids: itemIds,
        } as AnnotationQueueItemsAdd,
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
      queryClient.invalidateQueries({
        queryKey: [ANNOTATION_QUEUES_KEY],
      });
      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY],
      });
      return queryClient.invalidateQueries({
        queryKey: [SPANS_KEY],
      });
    },
  });
};

export const useAnnotationQueueItemsDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      annotationQueueId,
      itemIds,
    }: UseAnnotationQueueItemsDeleteMutationParams) => {
      const { data } = await api.post(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}/items/delete`,
        {
          ids: itemIds,
        } as AnnotationQueueItemsDelete,
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
      queryClient.invalidateQueries({
        queryKey: [ANNOTATION_QUEUES_KEY],
      });
      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY],
      });
      return queryClient.invalidateQueries({
        queryKey: [SPANS_KEY],
      });
    },
  });
};
