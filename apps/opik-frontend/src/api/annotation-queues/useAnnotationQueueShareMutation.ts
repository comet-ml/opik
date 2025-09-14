import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueue } from "@/types/annotation-queues";
import { useToast } from "@/components/ui/use-toast";

type UseAnnotationQueueShareMutationParams = {
  id: string;
};

const useAnnotationQueueShareMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ id }: UseAnnotationQueueShareMutationParams) => {
      const { data } = await api.post<AnnotationQueue>(
        `${ANNOTATION_QUEUES_REST_ENDPOINT}${id}/share`,
      );
      return data;
    },
    onSuccess: () => {
      toast({
        title: "Share token generated",
        description: "Queue can now be shared with SMEs",
      });

      // Invalidate and refetch annotation queues list
      queryClient.invalidateQueries({
        queryKey: ["annotation-queues"],
      });
    },
    onError: (error: AxiosError) => {
      const message = error?.message || "Failed to generate share token";
      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
  });
};

export default useAnnotationQueueShareMutation;
