import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useToast } from "@/components/ui/use-toast";
import api, { ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueueCreate } from "@/types/annotationQueues";

type UseAnnotationQueueCreateMutationParams = {
  workspaceName: string;
  projectId?: string;
  queue: AnnotationQueueCreate;
};

const useAnnotationQueueCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      workspaceName,
      projectId,
      queue,
    }: UseAnnotationQueueCreateMutationParams) => {
      const { data } = await api.post(ANNOTATION_QUEUES_REST_ENDPOINT, {
        ...queue,
        workspaceName,
        ...(projectId && { projectId }),
      });
      return data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: ["annotation-queues"],
      });
      toast({
        description: "Annotation queue created successfully",
      });
    },
    onError: () => {
      toast({
        title: "Error",
        description: "Failed to create annotation queue",
        variant: "destructive",
      });
    },
  });
};

export default useAnnotationQueueCreateMutation;