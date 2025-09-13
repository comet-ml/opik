import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api from "@/api/api";
import { SMEAnnotationSubmission } from "@/types/annotation-queues";
import { useToast } from "@/components/ui/use-toast";

type UseSMEAnnotationMutationParams = {
  shareToken: string;
  itemId: string;
  annotation: SMEAnnotationSubmission;
};

const useSMEAnnotationMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ shareToken, itemId, annotation }: UseSMEAnnotationMutationParams) => {
      const { data } = await api.post(
        `/v1/public/annotation-queues/${shareToken}/items/${itemId}/annotate`,
        annotation,
      );
      return data;
    },
    onSuccess: (_, { shareToken }) => {
      toast({
        title: "Annotation submitted",
        description: "Your feedback has been saved successfully",
      });
      
      // Invalidate progress to refresh completion status
      queryClient.invalidateQueries({
        queryKey: ["sme-queue-progress", shareToken],
      });
      
      // Invalidate queue items to refresh completion status
      queryClient.invalidateQueries({
        queryKey: ["sme-queue-items", shareToken],
      });
    },
    onError: (error: AxiosError) => {
      const message = error?.message || "Failed to submit annotation";
      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
  });
};

export default useSMEAnnotationMutation;
