import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { FEEDBACK_DEFINITIONS_REST_ENDPOINT } from "@/api/api";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseFeedbackDefinitionUpdateMutationParams = {
  feedbackDefinition: Partial<FeedbackDefinition>;
};

const useFeedbackDefinitionUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      feedbackDefinition,
    }: UseFeedbackDefinitionUpdateMutationParams) => {
      const { data } = await api.put(
        FEEDBACK_DEFINITIONS_REST_ENDPOINT + feedbackDefinition.id,
        feedbackDefinition,
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

export default useFeedbackDefinitionUpdateMutation;
