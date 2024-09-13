import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { FEEDBACK_DEFINITIONS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseFeedbackDefinitionDeleteMutationParams = {
  feedbackDefinitionId: string;
};

const useFeedbackDefinitionDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      feedbackDefinitionId,
    }: UseFeedbackDefinitionDeleteMutationParams) => {
      const { data } = await api.delete(
        FEEDBACK_DEFINITIONS_REST_ENDPOINT + feedbackDefinitionId,
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
        queryKey: ["feedback-definitions"],
      });
    },
  });
};

export default useFeedbackDefinitionDeleteMutation;
