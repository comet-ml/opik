import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useToast } from "@/components/ui/use-toast";
import api, { ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";

type UseAnnotationQueueDeleteMutationParams = {
  queueId: string;
  workspaceName: string;
  projectId?: string;
};

const useAnnotationQueueDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      queueId,
      workspaceName,
      projectId,
    }: UseAnnotationQueueDeleteMutationParams) => {
      const { data } = await api.delete(`${ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}`, {
        params: {
          workspaceName,
          ...(projectId && { projectId }),
        },
      });
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["annotation-queues"],
      });
      toast({
        description: "Annotation queue deleted successfully",
      });
    },
    onError: () => {
      toast({
        title: "Error",
        description: "Failed to delete annotation queue",
        variant: "destructive",
      });
    },
  });
};

export default useAnnotationQueueDeleteMutation;